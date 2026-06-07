package com.legacy.modernizer.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.AgentTaskRepository;
import com.legacy.modernizer.repository.ArtifactRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 3.3 — Documentation Generator Agent.
 *
 * <p>Given the source files of a generated Spring Boot microservice and its DDD
 * {@link ServiceBoundary}, produces three documents in a single LLM call:
 *
 * <ul>
 *   <li><b>openapi_spec</b> — Springdoc OpenAPI 3.0.3 YAML spec derived from the controller</li>
 *   <li><b>adr</b> — Architecture Decision Record (MADR format) for the service extraction</li>
 *   <li><b>runbook</b> — Migration runbook: pre-conditions, deployment, verification, rollback</li>
 * </ul>
 *
 * <p><b>Output format:</b> structured JSON — the LLM returns one JSON object whose
 * {@code documents} array contains three items, each with {@code type}, {@code filename},
 * and {@code content}. Jackson deserialises the response; content strings are automatically
 * unescaped so the stored YAML/Markdown is human-readable.
 *
 * <p>Each document is persisted as an {@link Artifact} with type
 * {@link ArtifactType#OPENAPI_SPEC}, {@link ArtifactType#ADR}, or
 * {@link ArtifactType#RUNBOOK}.
 */
@Component
public class DocGenAgent {

    private static final Logger log = LoggerFactory.getLogger(DocGenAgent.class);

    // -------------------------------------------------------------------------
    // System prompt — JSON output mode
    // -------------------------------------------------------------------------

    private static final String SYSTEM_PROMPT = """
            You are a software architect generating technical documentation for a Spring Boot 3 \
            microservice extracted from a legacy Java monolith.

            OUTPUT FORMAT — respond with ONLY a single valid JSON object, no prose, no markdown \
            code fences. The JSON must exactly match this schema:

            {
              "documents": [
                {
                  "type": "openapi_spec",
                  "filename": "openapi.yaml",
                  "content": "<complete OpenAPI 3.0.3 YAML — escape every newline as \\\\n>"
                },
                {
                  "type": "adr",
                  "filename": "adr-001-{service-name}.md",
                  "content": "<complete ADR markdown — escape newlines as \\\\n>"
                },
                {
                  "type": "runbook",
                  "filename": "runbook-{service-name}.md",
                  "content": "<complete runbook markdown — escape newlines as \\\\n>"
                }
              ]
            }

            CRITICAL JSON RULE: every newline inside a content string must be the two-character \
            sequence \\\\n. Every double-quote inside a content string must be \\\\". \
            The whole response must parse as valid JSON.

            ── OPENAPI SPEC RULES ──────────────────────────────────────────────────────
            • openapi: "3.0.3"
            • info: title = "{ServiceName} API", version: "1.0.0", description from boundary context
            • servers: [{url: "http://localhost:8080", description: "local dev"}]
            • Derive all paths from @RestController @RequestMapping and endpoint annotations
            • Standard CRUD paths: GET /, GET /{id}, POST /, PUT /{id}, DELETE /{id}
            • components/schemas: one schema for the entity (all fields), one for the request DTO
            • Entity id: type: integer, format: int64
            • Responses: 200 with application/json schema $ref, 204 (no body) for DELETE, \
              404 for single-resource endpoints

            ── ADR RULES (MADR format) ─────────────────────────────────────────────────
            # ADR-001: Extract {ServiceName} from Monolith
            ## Status
            Accepted
            ## Context
            (2-3 sentences: why this service exists, what the monolith coupling problem was)
            ## Decision
            (which DDD bounded context this is, which classes moved, why boundaries are here)
            ## Consequences
            **Positive:** independent deployability, focused team ownership, isolated failure domain\\n
            **Negative:** network latency on cross-service calls, eventual-consistency overhead
            ## Alternatives Considered
            - Keep in monolith (rejected: tight coupling prevents independent scaling)
            - Shared library (rejected: still couples deployment and increases version management cost)

            ── RUNBOOK RULES ───────────────────────────────────────────────────────────
            # Migration Runbook: {ServiceName}
            ## Pre-conditions
            (at least 4 bullet points: schema migration applied, env vars set, Postgres reachable, \
            downstream clients updated to call new base URL)
            ## Deployment Steps
            (numbered: 1. build, 2. run Flyway migration, 3. deploy container, 4. verify health)
            ## Verification Steps
            (curl commands for GET / and GET /{id}; check actuator/health; check metrics)
            ## Rollback Procedure
            (numbered: 1. redirect traffic to monolith, 2. redeploy previous version, \
            3. restore DB snapshot if schema changed)
            """;

    // -------------------------------------------------------------------------

    private final ChatLanguageModel   chatModel;
    private final AgentTaskRepository taskRepository;
    private final ArtifactRepository  artifactRepository;
    private final ObjectMapper        objectMapper;

    @Value("${anthropic.model:ollama}")
    private String modelName;

    public DocGenAgent(ChatLanguageModel chatModel,
                       AgentTaskRepository taskRepository,
                       ArtifactRepository artifactRepository,
                       ObjectMapper objectMapper) {
        this.chatModel         = chatModel;
        this.taskRepository    = taskRepository;
        this.artifactRepository = artifactRepository;
        this.objectMapper      = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates OpenAPI spec, ADR, and runbook for the given service boundary.
     *
     * @param jobId            migration job
     * @param boundary         DDD service boundary (from ArchitectAgent)
     * @param packageName      Java package fragment, e.g. {@code ownerservice}
     * @param entityName       primary entity simple name, e.g. {@code Owner}
     * @param controllerSource full source of the {@code {Entity}Controller.java} file
     * @param serviceSource    full source of the {@code {Entity}Service.java} file
     * @param entitySource     full source of the entity file (for schema derivation)
     * @param ragSnippets      test/doc pattern snippets from RAG (best-effort)
     */
    public DocGenerationResult generate(Long jobId,
                                        ServiceBoundary boundary,
                                        String packageName,
                                        String entityName,
                                        String controllerSource,
                                        String serviceSource,
                                        String entitySource,
                                        List<String> ragSnippets) {
        String serviceName = boundary.getServiceName();
        log.info("[doc-gen] Generating docs for {} (entity={}, job={})",
                serviceName, entityName, jobId);

        AgentTask task = taskRepository.save(AgentTask.builder()
                .jobId(jobId)
                .taskType("DOC_GEN")
                .status(AgentTaskStatus.PENDING)
                .classFqn(serviceName)
                .inputData(Map.of("serviceName", serviceName, "entityName", entityName))
                .build());

        task.setStatus(AgentTaskStatus.IN_PROGRESS);
        task = taskRepository.save(task);

        try {
            String userPrompt = buildUserPrompt(boundary, packageName, entityName,
                    controllerSource, serviceSource, entitySource, ragSnippets);

            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt)));
            String raw    = response.content().text();
            TokenUsage tu = response.tokenUsage();
            int tokens    = (tu != null) ? tu.totalTokenCount() : 0;
            log.info("[doc-gen] LLM response: {} chars, {} tokens", raw.length(), tokens);

            List<DocItem> items = parseJsonResponse(raw);
            log.info("[doc-gen] Parsed {} doc items for {}", items.size(), serviceName);

            if (items.isEmpty()) {
                throw new IllegalStateException(
                        "LLM produced no documents — raw length: " + raw.length());
            }

            List<GeneratedFile> files     = new ArrayList<>();
            List<Artifact>      artifacts = new ArrayList<>();

            for (DocItem item : items) {
                ArtifactType type   = resolveArtifactType(item.type());
                String       path   = "docs/" + item.filename();
                GeneratedFile gf    = new GeneratedFile(path, item.content());
                Artifact      saved = artifactRepository.save(Artifact.builder()
                        .jobId(jobId)
                        .taskId(task.getId())
                        .artifactType(type)
                        .classFqn(serviceName)
                        .filePath(path)
                        .content(item.content())
                        .build());
                files.add(gf);
                artifacts.add(saved);
                log.debug("[doc-gen] Saved {} → {}", type, path);
            }

            task.setStatus(AgentTaskStatus.COMPLETED);
            task.setModelUsed(modelName);
            task.setTokensUsed(tokens > 0 ? tokens : null);
            task.setOutputData(Map.of("docsGenerated", String.valueOf(files.size())));
            task = taskRepository.save(task);

            log.info("[doc-gen] Done — {} docs, {} tokens (task {})",
                    files.size(), tokens, task.getId());
            return new DocGenerationResult(task, artifacts, files);

        } catch (Exception e) {
            log.error("[doc-gen] Failed for {}: {}", serviceName, e.getMessage(), e);
            task.setStatus(AgentTaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
            throw new RuntimeException(
                    "Doc generation failed for " + serviceName + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // JSON parsing
    // -------------------------------------------------------------------------

    List<DocItem> parseJsonResponse(String raw) throws Exception {
        String json = extractJson(raw);
        DocResponse resp = objectMapper.readValue(json, DocResponse.class);
        if (resp.documents() == null) return List.of();
        return resp.documents().stream()
                .filter(d -> d.type() != null && d.filename() != null && d.content() != null
                        && !d.content().isBlank())
                .toList();
    }

    private String extractJson(String raw) {
        String s = raw.replaceAll("(?s)```[a-zA-Z]*\\s*", "").replaceAll("```", "").trim();
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    // -------------------------------------------------------------------------
    // Prompt
    // -------------------------------------------------------------------------

    String buildUserPrompt(ServiceBoundary boundary, String pkg, String entityName,
                           String controllerSource, String serviceSource,
                           String entitySource, List<String> ragSnippets) {
        String svc = boundary.getServiceName();
        StringBuilder sb = new StringBuilder();
        sb.append("Generate documentation for the ").append(svc).append(" microservice.\n\n");
        sb.append("Package   : com.modernized.").append(pkg).append("\n");
        sb.append("Entity    : ").append(entityName).append("\n");
        sb.append("Context   : ").append(nvl(boundary.getDescription())).append("\n");
        if (boundary.getRationale() != null)
            sb.append("Rationale : ").append(boundary.getRationale()).append("\n");
        sb.append("\nOriginal classes:\n");
        boundary.getClassFqns().forEach(c -> sb.append("  • ").append(c).append("\n"));

        sb.append("\n=== REST Controller (derive OpenAPI paths from this) ===\n")
                .append(controllerSource).append("\n\n");
        sb.append("=== Service layer ===\n")
                .append(serviceSource).append("\n\n");
        sb.append("=== Entity class (derive schema fields from this) ===\n")
                .append(entitySource).append("\n\n");

        if (!ragSnippets.isEmpty()) {
            sb.append("=== Reference patterns from original codebase ===\n");
            ragSnippets.forEach(s -> sb.append("```java\n").append(s.strip()).append("\n```\n\n"));
        }

        sb.append("Output the 3 documents (openapi_spec, adr, runbook) as a single JSON object.");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static ArtifactType resolveArtifactType(String type) {
        if (type == null) return ArtifactType.CONFIG;
        return switch (type.toLowerCase()) {
            case "openapi_spec" -> ArtifactType.OPENAPI_SPEC;
            case "adr"          -> ArtifactType.ADR;
            case "runbook"      -> ArtifactType.RUNBOOK;
            default             -> ArtifactType.CONFIG;
        };
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    // -------------------------------------------------------------------------
    // Internal JSON deserialization types
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocResponse(
            @JsonProperty("documents") List<DocItem> documents
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocItem(
            @JsonProperty("type")     String type,
            @JsonProperty("filename") String filename,
            @JsonProperty("content")  String content
    ) {}
}
