package com.legacy.modernizer.eval;

import com.legacy.modernizer.eval.metric.ApiCompletenessMetric;
import com.legacy.modernizer.eval.metric.CompilationMetric;
import com.legacy.modernizer.eval.metric.CoverageMetric;
import com.legacy.modernizer.eval.metric.LlmJudgeMetric;
import com.legacy.modernizer.eval.model.EvalMetric;
import com.legacy.modernizer.eval.model.MetricName;
import com.legacy.modernizer.eval.repository.EvalMetricRepository;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.repository.ArtifactRepository;
import com.legacy.modernizer.repository.MigrationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

// NOTE: evaluateAll(Path) is intentionally in EvaluatorController (not here)
// to avoid a circular dependency: evaluator → orchestrator.benchmark.BenchmarkSpec → evaluator.

/**
 * Phase 4.3 — Evaluator service.
 *
 * <p>Runs all four metrics for a given job and system, persists results to
 * {@code eval_metrics}, and returns the saved rows.
 *
 * <p>Entry points:
 * <ul>
 *   <li>{@link #evaluateMultiAgent(Long, Path)} — evaluate a multi-agent run</li>
 *   <li>{@link #evaluateBaseline(Long, String, Path)} — evaluate a baseline run for the
 *       same job's source directory</li>
 *   <li>{@link #evaluateAll(Path)} — evaluate multi-agent + both baselines for all repos</li>
 * </ul>
 */
@Service
public class EvaluatorService {

    private static final Logger log = LoggerFactory.getLogger(EvaluatorService.class);

    private static final String MULTI_AGENT     = "multi-agent";
    private static final String CLAUDE_BASELINE = "single-prompt-claude";
    private static final String GPT4O_BASELINE  = "single-prompt-gpt4o";

    private final CompilationMetric      compilationMetric;
    private final CoverageMetric         coverageMetric;
    private final ApiCompletenessMetric  apiCompletenessMetric;
    private final LlmJudgeMetric         llmJudgeMetric;
    private final EvalMetricRepository   evalMetricRepository;
    private final ArtifactRepository     artifactRepository;
    private final MigrationJobRepository jobRepository;

    @Value("${benchmark.results.dir:results}")
    private String resultsDirProp;

    public EvaluatorService(CompilationMetric compilationMetric,
                            CoverageMetric coverageMetric,
                            ApiCompletenessMetric apiCompletenessMetric,
                            LlmJudgeMetric llmJudgeMetric,
                            EvalMetricRepository evalMetricRepository,
                            ArtifactRepository artifactRepository,
                            MigrationJobRepository jobRepository) {
        this.compilationMetric     = compilationMetric;
        this.coverageMetric        = coverageMetric;
        this.apiCompletenessMetric = apiCompletenessMetric;
        this.llmJudgeMetric        = llmJudgeMetric;
        this.evalMetricRepository  = evalMetricRepository;
        this.artifactRepository    = artifactRepository;
        this.jobRepository         = jobRepository;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Runs all 4 metrics for the multi-agent system on a job.
     *
     * @param jobId         migration job ID
     * @param originalSrcDir root of the benchmark repo's Java source
     */
    @Transactional
    public List<EvalMetric> evaluateMultiAgent(Long jobId, Path originalSrcDir) {
        log.info("[evaluator] Multi-agent evaluation for job {}", jobId);
        List<Artifact> artifacts = artifactRepository.findByJobId(jobId);
        List<EvalMetric> saved = new ArrayList<>();

        // 1. Compilation
        CompilationMetric.Result comp = compilationMetric.evaluate(artifacts);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.COMPILATION_SUCCESS, comp.score(), comp.metadata()));

        // 2. Coverage
        CoverageMetric.Result cov = coverageMetric.evaluate(artifacts);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.COVERAGE, cov.score(), cov.metadata()));

        // 3. API completeness
        ApiCompletenessMetric.Result api = apiCompletenessMetric.evaluate(originalSrcDir, artifacts);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.API_COMPLETENESS, api.score(), api.metadata()));

        // 4. LLM judge
        LlmJudgeMetric.Result judge = llmJudgeMetric.evaluate(artifacts);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.LLM_JUDGE_SCORE, judge.score(), judge.metadata()));

        log.info("[evaluator] Multi-agent job {} — compile={} cov={} api={} judge={}",
                jobId, comp.score(), cov.score(), api.score(), judge.score());
        return saved;
    }

    /**
     * Runs all 4 metrics for a single-prompt baseline.
     *
     * @param jobId           the corresponding multi-agent job ID (shared for comparison)
     * @param systemId        {@code "single-prompt-claude"} or {@code "single-prompt-gpt4o"}
     * @param originalSrcDir  root of the benchmark repo's Java source
     */
    @Transactional
    public List<EvalMetric> evaluateBaseline(Long jobId, String systemId, Path originalSrcDir) {
        log.info("[evaluator] Baseline evaluation for job {} system={}", jobId, systemId);
        List<EvalMetric> saved = new ArrayList<>();

        Path systemDir  = resultsRoot().resolve(repoNameFor(jobId)).resolve(systemId);
        Path responseJson = systemDir.resolve("response.json");
        Path responseRaw  = systemDir.resolve("response_raw.txt");

        String rawResponse     = readJsonSafe(responseJson);       // JSON plan (for LLM judge)
        String rawResponseText = readRawSafe(responseRaw);          // full LLM output (for extractor)

        // 1. Compilation — extract & compile java blocks from raw response
        CompilationMetric.Result comp = compilationMetric.evaluateBaseline(systemId, rawResponseText);
        saved.add(persist(jobId, systemId, MetricName.COMPILATION_SUCCESS, comp.score(), comp.metadata()));

        // 2. Coverage — attempt if test classes exist in raw response
        CoverageMetric.Result cov = coverageMetric.evaluateBaseline(systemId, rawResponseText);
        saved.add(persist(jobId, systemId, MetricName.COVERAGE, cov.score(), cov.metadata()));

        // 3. API completeness — method-name overlap on extracted code, fallback to class names
        ApiCompletenessMetric.Result api =
                apiCompletenessMetric.evaluateBaseline(originalSrcDir, responseJson, systemId, rawResponseText);
        saved.add(persist(jobId, systemId, MetricName.API_COMPLETENESS, api.score(), api.metadata()));

        // 4. LLM judge — score the decomposition plan quality
        LlmJudgeMetric.Result judge = llmJudgeMetric.evaluateBaseline(rawResponse, systemId);
        saved.add(persist(jobId, systemId, MetricName.LLM_JUDGE_SCORE, judge.score(), judge.metadata()));

        log.info("[evaluator] Baseline {} job {} — compile={} cov={} api={} judge={}",
                systemId, jobId, comp.score(), cov.score(), api.score(), judge.score());
        return saved;
    }

    /** Returns all stored eval metrics for a job. */
    @Transactional(readOnly = true)
    public List<EvalMetric> findByJobId(Long jobId) {
        return evalMetricRepository.findByJobId(jobId);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private EvalMetric persist(Long jobId, String systemId, MetricName name,
                               double value, java.util.Map<String, Object> metadata) {
        EvalMetric m = EvalMetric.builder()
                .jobId(jobId)
                .systemId(systemId)
                .metricName(name)
                .metricValue(toBd(value))
                .metadata(metadata)
                .build();
        return evalMetricRepository.save(m);
    }

    private static BigDecimal toBd(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private Path resultsRoot() {
        return Path.of(resultsDirProp).toAbsolutePath().normalize();
    }

    private String repoNameFor(Long jobId) {
        return jobRepository.findById(jobId)
                .map(j -> j.getName().replace("benchmark-", ""))
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));
    }

    private static Long readJobId(Path metricsJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = om.readTree(metricsJson.toFile());
            com.fasterxml.jackson.databind.JsonNode jid = node.get("jobId");
            return jid != null ? jid.asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readJsonSafe(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "[]";
        } catch (java.io.IOException e) {
            return "[]";
        }
    }

    private static String readRawSafe(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }
}
