package com.legacy.modernizer.eval.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 4.3 — LLM Judge metric.
 *
 * <p>Uses Claude claude-sonnet-4-6 to score generated code (or a baseline's service
 * boundary plan) on five dimensions, each 1–10:
 * <ol>
 *   <li><b>correctness</b> — implements standard patterns and logic correctly</li>
 *   <li><b>readability</b> — clear naming, structure, no magic numbers</li>
 *   <li><b>idiomaticity</b> — Spring Boot 3 / Java 21 best practices</li>
 *   <li><b>completeness</b> — all expected components present</li>
 *   <li><b>dry</b> — code duplication minimised</li>
 * </ol>
 * <p>The final score is the arithmetic mean of the five sub-scores, on a 1–10 scale.
 *
 * <p>Requires {@code ANTHROPIC_API_KEY}.  Returns 0.0 with an error message
 * if the key is absent or the LLM call fails.
 */
@Component
public class LlmJudgeMetric {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeMetric.class);

    private static final String MODEL_ID        = "claude-sonnet-4-6";
    private static final int    MAX_CODE_CHARS  = 12_000;   // truncate large services
    private static final int    MAX_OUTPUT_TOKENS = 512;

    private static final String CODE_SYSTEM_PROMPT = """
            You are a senior Java code quality assessor. Evaluate the Spring Boot 3 \
            service code provided on exactly 5 dimensions. Score each 1 (very poor) to 10 (excellent).

            Dimensions:
            1. correctness   — implements standard CRUD patterns and business logic correctly
            2. readability   — clear naming, method length, no magic values, logical structure
            3. idiomaticity  — follows Java 21 + Spring Boot 3 conventions: jakarta.*, \
               constructor injection, @Transactional, ResponseEntity, JpaRepository
            4. completeness  — all expected components present: entity, DTO, repository, service, controller
            5. dry           — no repeated logic across methods or classes

            Return ONLY valid JSON — no prose, no code fences:
            {"correctness":N,"readability":N,"idiomaticity":N,"completeness":N,"dry":N}
            """;

    private static final String PLAN_SYSTEM_PROMPT = """
            You are a senior software architect evaluating a microservice decomposition plan \
            for a Java monolith. Score the plan on exactly 5 dimensions, each 1–10.

            Dimensions:
            1. correctness   — are the service boundaries architecturally sound?
            2. readability   — is the plan clearly structured with meaningful service names?
            3. idiomaticity  — do boundaries align with DDD bounded context and microservice \
               best practices?
            4. completeness  — does the plan cover all major system components?
            5. dry           — are boundaries distinct with no overlap between services?

            Return ONLY valid JSON — no prose, no code fences:
            {"correctness":N,"readability":N,"idiomaticity":N,"completeness":N,"dry":N}
            """;

    private final ObjectMapper om = new ObjectMapper();
    private ChatLanguageModel  judgeModel;
    private boolean            available;

    @PostConstruct
    void init() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[llm-judge] ANTHROPIC_API_KEY not set — metric will return 0.0");
            available = false;
            return;
        }
        judgeModel = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(MODEL_ID)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(120))
                .build();
        available = true;
        log.info("[llm-judge] Initialised — model={}", MODEL_ID);
    }

    public record Result(
            double              score,          // mean of 5 sub-scores (1–10 scale)
            Map<String, Object> subScores,
            Map<String, Object> metadata
    ) {}

    // ─── Multi-agent ──────────────────────────────────────────────────────────

    /**
     * Judges the quality of generated SERVICE_CODE artifacts.
     * Concatenates all service code up to {@link #MAX_CODE_CHARS} then calls Claude.
     */
    public Result evaluate(List<Artifact> artifacts) {
        if (!available) return unavailable();

        String codeSample = artifacts.stream()
                .filter(a -> a.getArtifactType() == ArtifactType.SERVICE_CODE)
                .filter(a -> a.getContent() != null && !a.getContent().isBlank())
                .map(Artifact::getContent)
                .collect(Collectors.joining("\n\n// ─────────────────\n\n"));

        if (codeSample.isBlank()) {
            return new Result(0.0, Map.of(), Map.of("reason", "no SERVICE_CODE content"));
        }
        if (codeSample.length() > MAX_CODE_CHARS) {
            codeSample = codeSample.substring(0, MAX_CODE_CHARS) + "\n// ... (truncated)";
        }

        return callJudge(CODE_SYSTEM_PROMPT, codeSample, "multi-agent");
    }

    // ─── Baseline ─────────────────────────────────────────────────────────────

    /** Judges the quality of a baseline's service boundary plan JSON. */
    public Result evaluateBaseline(String rawPlanJson, String systemId) {
        if (!available) return unavailable();
        if (rawPlanJson == null || rawPlanJson.isBlank()) {
            return new Result(0.0, Map.of(), Map.of("reason", "empty baseline response"));
        }

        String content = rawPlanJson.length() > MAX_CODE_CHARS
                ? rawPlanJson.substring(0, MAX_CODE_CHARS) + "... (truncated)"
                : rawPlanJson;

        return callJudge(PLAN_SYSTEM_PROMPT, content, systemId);
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private Result callJudge(String systemPrompt, String content, String tag) {
        try {
            String raw = judgeModel.generate(
                    List.of(
                            dev.langchain4j.data.message.SystemMessage.from(systemPrompt),
                            dev.langchain4j.data.message.UserMessage.from(content)
                    )).content().text();

            Map<String, Object> subScores = parseSubScores(raw);
            double avg = computeAverage(subScores);

            log.info("[llm-judge][{}] score={} sub={}", tag, avg, subScores);
            return new Result(avg, subScores, Map.of("rawResponse", raw));

        } catch (Exception e) {
            log.error("[llm-judge][{}] Call failed: {}", tag, e.getMessage(), e);
            return new Result(0.0, Map.of(), Map.of("error", e.getMessage()));
        }
    }

    public Map<String, Object> parseSubScores(String raw) {
        try {
            String json = raw.replaceAll("(?s)```[a-zA-Z]*\\s*", "").replaceAll("```", "").strip();
            int start = json.indexOf('{'), end = json.lastIndexOf('}');
            if (start < 0 || end <= start) return Map.of();
            JsonNode node = om.readTree(json.substring(start, end + 1));
            Map<String, Object> scores = new HashMap<>();
            node.fields().forEachRemaining(e -> scores.put(e.getKey(), e.getValue().asDouble()));
            return scores;
        } catch (Exception e) {
            log.warn("[llm-judge] Cannot parse sub-scores from: {}", raw);
            return Map.of();
        }
    }

    public static double computeAverage(Map<String, Object> subScores) {
        if (subScores.isEmpty()) return 0.0;
        double sum = 0;
        for (Object v : subScores.values()) {
            sum += v instanceof Number n ? n.doubleValue() : 0.0;
        }
        return sum / subScores.size();
    }

    private static Result unavailable() {
        return new Result(0.0, Map.of(), Map.of("reason", "ANTHROPIC_API_KEY not set"));
    }
}
