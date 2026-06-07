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

        // 2. Call LLM
        String userPrompt = USER_PROMPT_TEMPLATE.formatted(sources.concatenated());
        log.info("[{}][{}] Calling model — {} chars, {} files",
                systemId(), repoName, sources.charsSent(), sources.filesIncluded());

        String  rawResponse;
        Integer tokensUsed;
        try {
            Response<AiMessage> resp = model().generate(
                    List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt)));
            rawResponse = resp.content().text();
            tokensUsed  = resp.tokenUsage() != null ? resp.tokenUsage().totalTokenCount() : null;
            log.info("[{}][{}] Response {} chars, {} tokens",
                    systemId(), repoName, rawResponse.length(), tokensUsed);
        } catch (Exception e) {
            log.error("[{}][{}] LLM call failed: {}", systemId(), repoName, e.getMessage(), e);
            return failed(repoName, e.getMessage(), sources, 0, System.currentTimeMillis() - start);
        }

        // 3. Parse JSON response
        int serviceCount = countServices(rawResponse);
        log.info("[{}][{}] Parsed {} service boundaries", systemId(), repoName, serviceCount);

        // 4. Save to results/
        long elapsed = System.currentTimeMillis() - start;
        try {
            saveOutputs(resultsRoot, repoName, sources, rawResponse, serviceCount,
                    tokensUsed, elapsed);
        } catch (IOException e) {
            log.warn("[{}][{}] Could not save outputs: {}", systemId(), repoName, e.getMessage());
        }

        log.info("[{}] ✓ {} done in {}s — {} services",
                repoName, systemId(), elapsed / 1000, serviceCount);

        return new BaselineResult(repoName, systemId(), modelId(), true,
                rawResponse, sources.filesIncluded(), sources.filesSkipped(),
                sources.charsSent(), tokensUsed, elapsed, serviceCount,
                null, LocalDateTime.now());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void saveOutputs(Path resultsRoot, String repoName,
                             SourceCollector.CollectedSources sources,
                             String rawResponse, int serviceCount,
                             Integer tokensUsed, long elapsedMs) throws IOException {
        Path outDir = resultsRoot.resolve(repoName).resolve(systemId());
        Files.createDirectories(outDir);

        // response_raw.txt
        Files.writeString(outDir.resolve("response_raw.txt"), rawResponse);

        // response.json — best-effort parsed array
        String responseJson = extractJson(rawResponse);
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
