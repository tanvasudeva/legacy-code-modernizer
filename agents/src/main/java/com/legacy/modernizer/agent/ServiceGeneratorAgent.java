package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.AgentTaskRepository;
import com.legacy.modernizer.repository.ArtifactRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3.1 — Service Generator Agent.
 *
 * <p>Given a {@link ServiceBoundary} (from the ArchitectAgent) and optional RAG context
 * snippets retrieved from the original codebase, generates a complete Spring Boot 3
 * microservice: pom.xml, Application, entity, DTO, repository, service, controller.
 *
 * <p>Flow:
 * <ol>
 *   <li>Creates an {@link AgentTask} (task_type = SERVICE_GEN, PENDING → IN_PROGRESS).</li>
 *   <li>Calls the LLM with a structured prompt that demands {@code <file>} XML blocks.</li>
 *   <li>Parses every {@code <file><path>…</path><content>…</content></file>} block.</li>
 *   <li>Persists each parsed file as an {@link Artifact}(SERVICE_CODE).</li>
 *   <li>Marks the task COMPLETED (or FAILED on error).</li>
 * </ol>
 */
@Component
public class ServiceGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(ServiceGeneratorAgent.class);

    // Matches:  <file>\n<path>…</path>\n<content>…</content>\n</file>
    private static final Pattern FILE_BLOCK = Pattern.compile(
            "<file>\\s*<path>(.*?)</path>\\s*<content>(.*?)</content>\\s*</file>",
            Pattern.DOTALL
    );

    // -------------------------------------------------------------------------
    // System prompt — governs format + every compilation-critical rule
    // -------------------------------------------------------------------------

    private static final String SYSTEM_PROMPT = """
            You are a Java architect generating production-ready Spring Boot 3 microservices \
            from DDD service boundary analysis.

            OUTPUT FORMAT — produce ONLY the XML file blocks below. \
            No markdown. No prose. No explanation.

            <file>
            <path>pom.xml</path>
            <content>
            ... complete pom.xml content ...
            </content>
            </file>

            <file>
            <path>src/main/java/com/modernized/{pkg}/Application.java</path>
            <content>
            ... complete Java source ...
            </content>
            </file>

            ... (one <file> block per generated file)

            MANDATORY RULES — violation causes compilation failure:
            1.  Java 21, Spring Boot 3.2.5; use jakarta.* (NOT javax.*) throughout.
            2.  groupId = com.modernized; artifactId = {service-name}.
            3.  Package root: com.modernized.{pkg}  (all kebab/hyphens removed, lowercase).
            4.  pom.xml MUST include: spring-boot-starter-web, spring-boot-starter-data-jpa, \
                postgresql (runtime scope), lombok (optional scope).
            5.  Entity classes: annotations in order — @Data @Builder @NoArgsConstructor \
                @AllArgsConstructor @Entity @Table(name="…"). \
                Primary key: private Long id; with @Id @GeneratedValue(strategy=GenerationType.IDENTITY).
            6.  Repository: public interface {Entity}Repository extends JpaRepository<{Entity}, Long> {} \
                — one interface per entity, no implementation.
            7.  Service: @Service @Transactional; all fields final; constructor injection \
                (no @Autowired); standard CRUD — findAll, findById, create, update, delete.
            8.  Controller: @RestController @RequestMapping("/api/{resource}"); use ResponseEntity<>; \
                endpoints: GET /, GET /{id}, POST / (body = entity DTO), \
                PUT /{id} (body = entity DTO), DELETE /{id}.
            9.  Application.java: @SpringBootApplication in root package com.modernized.{pkg}; \
                public static void main(String[] args) { SpringApplication.run(…); }.
            10. ALL imports must be explicit — zero wildcard imports.
            11. Do NOT include spring.datasource properties in Application.java. \
                The service is configured externally.
            """;

    // -------------------------------------------------------------------------

    private final ChatLanguageModel chatModel;
    private final AgentTaskRepository taskRepository;
    private final ArtifactRepository  artifactRepository;

    @Value("${anthropic.model:ollama}")
    private String modelName;

    public ServiceGeneratorAgent(ChatLanguageModel chatModel,
                                 AgentTaskRepository taskRepository,
                                 ArtifactRepository artifactRepository) {
        this.chatModel         = chatModel;
        this.taskRepository    = taskRepository;
        this.artifactRepository = artifactRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates a complete Spring Boot 3 microservice for the given boundary.
     *
     * @param jobId           the migration job
     * @param boundary        DDD service boundary from the ArchitectAgent
     * @param contextSnippets RAG-retrieved code snippets from the original codebase
     *                        (pass empty list if Qdrant is unavailable)
     * @return tracking record with the persisted task, artifact list, and in-memory files
     */
    public ServiceGenerationResult generate(Long jobId,
                                            ServiceBoundary boundary,
                                            List<String> contextSnippets) {
        String serviceName = boundary.getServiceName();
        String pkg         = toPackageName(serviceName);
        log.info("[service-gen] Generating {} (pkg={}) for job {}", serviceName, pkg, jobId);

        // 1. Create AgentTask (PENDING)
        AgentTask task = taskRepository.save(AgentTask.builder()
                .jobId(jobId)
                .taskType("SERVICE_GEN")
                .status(AgentTaskStatus.PENDING)
                .classFqn(serviceName)
                .inputData(Map.of(
                        "serviceName",     serviceName,
                        "boundaryId",      boundary.getId() != null ? boundary.getId().toString() : "",
                        "classFqnCount",   String.valueOf(boundary.getClassFqns().size())
                ))
                .build());

        // 2. Advance to IN_PROGRESS
        task.setStatus(AgentTaskStatus.IN_PROGRESS);
        task = taskRepository.save(task);

        try {
            // 3. Call LLM
            String userPrompt = buildUserPrompt(boundary, pkg, contextSnippets);
            log.debug("[service-gen] Prompt length: {} chars", userPrompt.length());

            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt))
            );
            String raw    = response.content().text();
            TokenUsage tu = response.tokenUsage();
            int tokens    = (tu != null) ? tu.totalTokenCount() : 0;
            log.info("[service-gen] LLM response: {} chars, {} tokens", raw.length(), tokens);

            // 4. Parse <file> blocks
            List<GeneratedFile> files = parseFileBlocks(raw);
            log.info("[service-gen] Parsed {} files for {}", files.size(), serviceName);

            if (files.isEmpty()) {
                throw new IllegalStateException(
                        "LLM produced no <file> blocks — raw response length: " + raw.length());
            }

            // 5. Persist each file as an Artifact
            List<Artifact> artifacts = new ArrayList<>();
            for (GeneratedFile f : files) {
                Artifact a = artifactRepository.save(Artifact.builder()
                        .jobId(jobId)
                        .taskId(task.getId())
                        .artifactType(ArtifactType.SERVICE_CODE)
                        .classFqn(serviceName)
                        .filePath(f.filePath())
                        .content(f.content())
                        .build());
                artifacts.add(a);
                log.debug("[service-gen] Saved artifact: {}", f.filePath());
            }

            // 6. Mark task COMPLETED
            task.setStatus(AgentTaskStatus.COMPLETED);
            task.setModelUsed(modelName);
            task.setTokensUsed(tokens > 0 ? tokens : null);
            task.setOutputData(Map.of(
                    "filesGenerated", String.valueOf(files.size()),
                    "serviceName",    serviceName
            ));
            task = taskRepository.save(task);

            log.info("[service-gen] Done — {} files, {} tokens (task {})",
                    files.size(), tokens, task.getId());
            return new ServiceGenerationResult(task, artifacts, files);

        } catch (Exception e) {
            log.error("[service-gen] Failed for {}: {}", serviceName, e.getMessage(), e);
            task.setStatus(AgentTaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
            throw new RuntimeException("Service generation failed for " + serviceName
                    + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Prompt construction
    // -------------------------------------------------------------------------

    String buildUserPrompt(ServiceBoundary boundary, String pkg, List<String> contextSnippets) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a Spring Boot 3 microservice.\n\n");

        sb.append("Service name  : ").append(boundary.getServiceName()).append("\n");
        sb.append("Package       : com.modernized.").append(pkg).append("\n");
        sb.append("Domain context: ").append(nvl(boundary.getDescription())).append("\n");
        if (boundary.getRationale() != null) {
            sb.append("Rationale     : ").append(boundary.getRationale()).append("\n");
        }
        sb.append("\n");

        sb.append("Original classes in this service:\n");
        for (String fqn : boundary.getClassFqns()) {
            sb.append("  • ").append(fqn).append("\n");
        }
        sb.append("\n");

        if (!contextSnippets.isEmpty()) {
            sb.append("Reference code retrieved from the original codebase:\n\n");
            for (String snippet : contextSnippets) {
                sb.append("```java\n").append(snippet.strip()).append("\n```\n\n");
            }
        }

        sb.append("Generate ALL of the following files inside <file> blocks:\n");
        sb.append("  1. pom.xml\n");
        sb.append("  2. src/main/java/com/modernized/").append(pkg).append("/Application.java\n");
        sb.append("  3. src/main/java/com/modernized/").append(pkg)
                .append("/entity/{PrimaryEntity}.java\n");
        sb.append("  4. src/main/java/com/modernized/").append(pkg)
                .append("/dto/{PrimaryEntity}Request.java\n");
        sb.append("  5. src/main/java/com/modernized/").append(pkg)
                .append("/repository/{PrimaryEntity}Repository.java\n");
        sb.append("  6. src/main/java/com/modernized/").append(pkg)
                .append("/service/{PrimaryEntity}Service.java\n");
        sb.append("  7. src/main/java/com/modernized/").append(pkg)
                .append("/controller/{PrimaryEntity}Controller.java\n");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // File block parsing
    // -------------------------------------------------------------------------

    List<GeneratedFile> parseFileBlocks(String raw) {
        List<GeneratedFile> files = new ArrayList<>();
        Matcher m = FILE_BLOCK.matcher(raw);
        while (m.find()) {
            String path    = m.group(1).strip();
            String content = m.group(2).strip();
            if (!path.isEmpty() && !content.isEmpty()) {
                files.add(new GeneratedFile(path, content));
            }
        }
        return files;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** "owner-service" → "ownerservice" */
    static String toPackageName(String serviceName) {
        return serviceName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
