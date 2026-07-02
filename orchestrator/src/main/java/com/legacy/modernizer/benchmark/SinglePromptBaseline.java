package com.legacy.modernizer.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Abstract base for single-prompt baseline systems.
 *
 * <p>Both baselines share the same prompt, source-collection logic, result-saving
 * strategy, and JSON-parsing attempt.  Subclasses only supply the model and system ID.
 *
 * <p>The single prompt mirrors the goal of the multi-agent pipeline but compresses it
 * into one LLM call with no RAG, no iterative refinement, and no structured output
 * enforcement beyond the system-prompt instruction.
 *
 * <p><b>Output written per run:</b>
 * <ul>
 *   <li>{@code response_raw.txt}  — verbatim LLM output</li>
 *   <li>{@code response.json}     — parsed JSON array (empty array if unparseable)</li>
 *   <li>{@code metadata.json}     — timing, file counts, token usage</li>
 * </ul>
 */
public abstract class SinglePromptBaseline {

    private static final Logger log = LoggerFactory.getLogger(SinglePromptBaseline.class);

    /** Rough chars-per-token estimate used to size the context window. */
    private static final int CHARS_PER_TOKEN = 4;

    // ─── Shared single prompt ────────────────────────────────────────────────

    static final String SYSTEM_PROMPT = """
            You are a senior software architect. Your task is to decompose a Java monolithic \
            application into microservices following Domain-Driven Design principles.

            Respond with ONLY a JSON array — no prose, no markdown code fences.
            Each element must have exactly these fields:
              "serviceName"   — hyphenated slug (e.g., "owner-service")
              "classFqns"     — array of fully-qualified class names belonging to this service
              "description"   — 2-3 sentence domain description
              "rationale"     — why these classes form a cohesive bounded context
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            Decompose the following Java monolith into microservices.
            Return ONLY a JSON array of service boundary objects as specified.

            <source>
            %s
            </source>
            """;

    // ─── Code-generation prompt (Step 2 — one call per service) ─────────────

    private static final String CODE_SYSTEM_PROMPT = """
            You are a Java developer. Generate a complete Spring Boot 3 microservice.

            For each class output a separate ```java code block. \
            Every block must contain exactly one public class or interface \
            with the correct package declaration.

            RULES:
            1. Java 21, Spring Boot 3, Jakarta EE (jakarta.persistence.*, jakarta.validation.*)
            2. Annotate entities with @Entity and @Table; repositories extend JpaRepository<T, Long>
            3. Service class: @Service, @Transactional on write methods, constructor injection
            4. Controller: @RestController, @RequestMapping, standard CRUD endpoints
            5. Implement ALL methods implied by the class names — do not leave stubs
            6. No explanatory prose outside the code blocks
            """;

    // ─── Template method ─────────────────────────────────────────────────────

    /** The LLM to call — provided by each concrete subclass. */
    protected abstract ChatLanguageModel model();

    /** Identifier written to result files (e.g., "single-prompt-gpt4o"). */
    protected abstract String systemId();

    /** Model identifier written to result metadata (e.g., "gpt-4o"). */
    protected abstract String modelId();

    /**
     * Context-window budget in tokens.  The source collector will cap collected
     * chars at {@code contextWindowTokens() * CHARS_PER_TOKEN}.
     */
    protected abstract int contextWindowTokens();

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Runs the single-prompt baseline for the given repository.
     *
     * @param spec        benchmark target
     * @param projectRoot project root for resolving relative src paths
     * @param resultsRoot root of the results/ directory
     */
    public BaselineResult run(BenchmarkSpec spec, Path projectRoot, Path resultsRoot) {
        long   start    = System.currentTimeMillis();
        String repoName = spec.name();
        log.info("[{}] ▶ {} (~{} LOC)", systemId(), repoName, spec.approxLoc());

        // 1. Collect source files
        int charBudget = contextWindowTokens() * CHARS_PER_TOKEN;
        SourceCollector collector = new SourceCollector(charBudget);
        Path srcDir = spec.resolveSrc(projectRoot);
        SourceCollector.CollectedSources sources = collector.collect(srcDir);

        if (sources.filesIncluded() == 0) {
            String msg = "No Java files found at: " + srcDir;
            log.error("[{}] {}", systemId(), msg);
            return failed(repoName, msg, sources, 0, System.currentTimeMillis() - start);
        }

        // 2. Call LLM — Step 1: decomposition plan
        String userPrompt = USER_PROMPT_TEMPLATE.formatted(sources.concatenated());
        log.info("[{}][{}] Step 1 — decomposition plan ({} chars, {} files)",
                systemId(), repoName, sources.charsSent(), sources.filesIncluded());

        String  planResponse;
        int     totalTokens = 0;
        try {
            Response<AiMessage> resp = model().generate(
                    List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt)));
            planResponse = resp.content().text();
            if (resp.tokenUsage() != null) totalTokens += resp.tokenUsage().totalTokenCount();
            log.info("[{}][{}] Plan response: {} chars", systemId(), repoName, planResponse.length());
        } catch (Exception e) {
            log.error("[{}][{}] Plan call failed: {}", systemId(), repoName, e.getMessage(), e);
            return failed(repoName, e.getMessage(), sources, 0, System.currentTimeMillis() - start);
        }

        // 3. Parse plan and count services
        int serviceCount = countServices(planResponse);
        log.info("[{}][{}] Parsed {} service boundaries", systemId(), repoName, serviceCount);

        // 4. Step 2: generate code per service (no RAG, no repair — fair baseline comparison)
        //    Appended to response_raw.txt so the evaluator's BaselineCodeExtractor picks it up.
        StringBuilder codeOutput = new StringBuilder(planResponse);
        if (serviceCount > 0) {
            log.info("[{}][{}] Step 2 — generating code for {} services", systemId(), repoName, serviceCount);
            int codeTokens = generateServiceCode(planResponse, codeOutput);
            totalTokens += codeTokens;
            log.info("[{}][{}] Code generation complete — {} additional tokens", systemId(), repoName, codeTokens);
        }

        String rawResponse = codeOutput.toString();

        // 5. Save to results/
        long elapsed = System.currentTimeMillis() - start;
        try {
            saveOutputs(resultsRoot, repoName, sources, planResponse, rawResponse,
                    serviceCount, totalTokens > 0 ? totalTokens : null, elapsed);
        } catch (IOException e) {
            log.warn("[{}][{}] Could not save outputs: {}", systemId(), repoName, e.getMessage());
        }

        log.info("[{}] ✓ {} done in {}s — {} services, {} tokens",
                repoName, systemId(), elapsed / 1000, serviceCount, totalTokens);

        return new BaselineResult(repoName, systemId(), modelId(), true,
                rawResponse, sources.filesIncluded(), sources.filesSkipped(),
                sources.charsSent(), totalTokens > 0 ? totalTokens : null,
                elapsed, serviceCount, null, LocalDateTime.now());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void saveOutputs(Path resultsRoot, String repoName,
                             SourceCollector.CollectedSources sources,
                             String planResponse, String fullRawResponse,
                             int serviceCount, Integer tokensUsed, long elapsedMs) throws IOException {
        Path outDir = resultsRoot.resolve(repoName).resolve(systemId());
        Files.createDirectories(outDir);

        // response_raw.txt — plan + all generated code blocks (read by BaselineCodeExtractor)
        Files.writeString(outDir.resolve("response_raw.txt"), fullRawResponse);

        // response.json — parsed service boundary plan only
        String responseJson = extractJson(planResponse);
        Files.writeString(outDir.resolve("response.json"), responseJson);

        // metadata.json
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Map<String, Object> meta = Map.of(
                "repoName",        repoName,
                "system",          systemId(),
                "modelId",         modelId(),
                "filesIncluded",   sources.filesIncluded(),
                "filesSkipped",    sources.filesSkipped(),
                "charsSent",       sources.charsSent(),
                "tokensUsed",      tokensUsed != null ? tokensUsed : -1,
                "servicesExtracted", serviceCount,
                "elapsedMs",       elapsedMs,
                "completedAt",     LocalDateTime.now().toString()
        );
        om.writerWithDefaultPrettyPrinter()
          .writeValue(outDir.resolve("metadata.json").toFile(), meta);
    }

    /**
     * For each service in the plan, makes one code-generation LLM call (no RAG, no repair)
     * and appends the response to {@code out}. Returns total tokens used across all calls.
     */
    private int generateServiceCode(String planResponse, StringBuilder out) {
        String json = extractJson(planResponse);
        int tokens = 0;
        try {
            JsonNode services = new ObjectMapper().readTree(json);
            if (!services.isArray()) return 0;
            for (JsonNode svc : services) {
                String name = svc.path("serviceName").asText("unknown-service");
                String desc = svc.path("description").asText("");
                List<String> fqns = new ArrayList<>();
                svc.path("classFqns").forEach(n -> fqns.add(n.asText()));

                String userPrompt = buildCodeUserPrompt(name, desc, fqns);
                log.info("[{}] Generating code for {}", systemId(), name);
                try {
                    Response<AiMessage> resp = model().generate(List.of(
                            SystemMessage.from(CODE_SYSTEM_PROMPT),
                            UserMessage.from(userPrompt)));
                    out.append("\n\n// ═══ ").append(name).append(" ═══\n")
                       .append(resp.content().text());
                    if (resp.tokenUsage() != null) tokens += resp.tokenUsage().totalTokenCount();
                } catch (Exception e) {
                    log.warn("[{}] Code gen failed for {}: {}", systemId(), name, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[{}] Could not parse plan for code generation: {}", systemId(), e.getMessage());
        }
        return tokens;
    }

    private static String buildCodeUserPrompt(String serviceName, String description,
                                              List<String> classFqns) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a Spring Boot 3 microservice for: ").append(serviceName).append("\n\n");
        if (!description.isBlank()) {
            sb.append("Domain description: ").append(description).append("\n\n");
        }
        sb.append("Classes to implement:\n");
        classFqns.forEach(c -> sb.append("  - ").append(c).append("\n"));
        sb.append("\nGenerate complete, compilable code for every class listed. ")
          .append("Each file in its own ```java code block with the correct package declaration.");
        return sb.toString();
    }

    /** Strips code fences then returns the JSON array, or {@code []} on failure. */
    static String extractJson(String raw) {
        String s = raw.replaceAll("(?s)```[a-zA-Z]*\\s*", "").replaceAll("```", "").strip();
        int start = s.indexOf('[');
        int end   = s.lastIndexOf(']');
        if (start < 0 || end <= start) return "[]";
        return s.substring(start, end + 1);
    }

    /** Returns the number of JSON objects in the array, or -1 if unparseable. */
    static int countServices(String raw) {
        try {
            String json = extractJson(raw);
            ObjectMapper om = new ObjectMapper();
            JsonNode node = om.readTree(json);
            return node.isArray() ? node.size() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private BaselineResult failed(String repoName, String msg,
                                  SourceCollector.CollectedSources sources,
                                  int serviceCount, long elapsedMs) {
        return new BaselineResult(repoName, systemId(), modelId(), false,
                null, sources.filesIncluded(), sources.filesSkipped(),
                sources.charsSent(), null, elapsedMs, serviceCount,
                msg, LocalDateTime.now());
    }
}
