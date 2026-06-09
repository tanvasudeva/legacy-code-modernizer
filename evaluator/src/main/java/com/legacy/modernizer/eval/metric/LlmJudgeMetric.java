package com.legacy.modernizer.eval.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Phase 4.3/4.6 — LLM Judge metric.
 *
 * <p>Delegates all LLM calls to {@link CrossModelJudge}, which routes each
 * evaluation to the <em>opposing</em> model family:
 * <ul>
 *   <li>Multi-agent output (Claude-generated) → judged by GPT-4o</li>
 *   <li>GPT-4o baseline output → judged by Claude</li>
 *   <li>Claude baseline output → judged by GPT-4o</li>
 * </ul>
 *
 * <p>The judge scores five dimensions (1–10 each) and returns their arithmetic mean.
 * The {@link Result} exposes {@link Result#judgeModelId()} so the caller can persist
 * which model performed the evaluation, enabling independent verification.
 */
@Component
public class LlmJudgeMetric {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeMetric.class);

    static final int MAX_CODE_CHARS = 12_000;

    public static final String CODE_SYSTEM_PROMPT = """
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

    static final String PLAN_SYSTEM_PROMPT = """
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

    private final CrossModelJudge crossModelJudge;

    public LlmJudgeMetric(CrossModelJudge crossModelJudge) {
        this.crossModelJudge = crossModelJudge;
    }

    /**
     * Result of one judge evaluation.
     *
     * @param score        arithmetic mean of 5 sub-scores (1–10 scale)
     * @param judgeModelId the model that performed the evaluation (e.g. {@code "gpt-4o"})
     * @param subScores    individual dimension scores
     * @param metadata     raw response and any error details
     */
    public record Result(
            double              score,
            String              judgeModelId,
            Map<String, Object> subScores,
            Map<String, Object> metadata
    ) {}

    // ─── Multi-agent ──────────────────────────────────────────────────────────

    /**
     * Judges the quality of generated SERVICE_CODE artifacts.
     * Routes to GPT-4o judge (Claude generated this output).
     *
     * @param artifacts  all artifacts for the job
     * @param systemId   must be {@code "multi-agent"}
     */
    public Result evaluate(List<Artifact> artifacts, String systemId) {
        if (!crossModelJudge.isAvailable()) return unavailable(systemId);

        String codeSample = artifacts.stream()
                .filter(a -> a.getArtifactType() == ArtifactType.SERVICE_CODE)
                .filter(a -> a.getContent() != null && !a.getContent().isBlank())
                .map(Artifact::getContent)
                .collect(Collectors.joining("\n\n// ─────────────────\n\n"));

        if (codeSample.isBlank()) {
            return new Result(0.0, crossModelJudge.resolveJudgeModelId(systemId),
                    Map.of(), Map.of("reason", "no SERVICE_CODE content"));
        }
        if (codeSample.length() > MAX_CODE_CHARS) {
            codeSample = codeSample.substring(0, MAX_CODE_CHARS) + "\n// ... (truncated)";
        }

        CrossModelJudge.JudgeResult r = crossModelJudge.judge(systemId, codeSample, CODE_SYSTEM_PROMPT);
        return toResult(r);
    }

    // ─── Baseline ─────────────────────────────────────────────────────────────

    /**
     * Judges the quality of a baseline's service boundary plan or extracted code.
     * Routes to the appropriate opposing model.
     *
     * @param content  raw response text (JSON plan or extracted code)
     * @param systemId {@code "single-prompt-claude"} or {@code "single-prompt-gpt4o"}
     */
    public Result evaluateBaseline(String content, String systemId) {
        if (!crossModelJudge.isAvailable()) return unavailable(systemId);
        if (content == null || content.isBlank()) {
            return new Result(0.0, crossModelJudge.resolveJudgeModelId(systemId),
                    Map.of(), Map.of("reason", "empty baseline response"));
        }

        String truncated = content.length() > MAX_CODE_CHARS
                ? content.substring(0, MAX_CODE_CHARS) + "... (truncated)"
                : content;

        CrossModelJudge.JudgeResult r = crossModelJudge.judge(systemId, truncated, PLAN_SYSTEM_PROMPT);
        return toResult(r);
    }

    // ─── Static parsing helpers (testable without model instantiation) ─────────

    /** Parses a JSON sub-score response from the judge model. */
    public static Map<String, Object> parseSubScoresStatic(String raw) {
        try {
            ObjectMapper om = new ObjectMapper();
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

    /**
     * Instance-level wrapper for backward compatibility with tests
     * that instantiate {@link LlmJudgeMetric} to call parse helpers.
     */
    public Map<String, Object> parseSubScores(String raw) {
        return parseSubScoresStatic(raw);
    }

    public static double computeAverage(Map<String, Object> subScores) {
        if (subScores.isEmpty()) return 0.0;
        double sum = 0;
        for (Object v : subScores.values()) {
            sum += v instanceof Number n ? n.doubleValue() : 0.0;
        }
        return sum / subScores.size();
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private static Result toResult(CrossModelJudge.JudgeResult r) {
        return new Result(r.score(), r.judgeModelId(), r.subScores(), r.metadata());
    }

    private Result unavailable(String systemId) {
        String judgeId = crossModelJudge.resolveJudgeModelId(systemId);
        return new Result(0.0, judgeId, Map.of(),
                Map.of("reason", "no judge model available", "judgeModel", judgeId));
    }
}
