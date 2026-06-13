package com.legacy.modernizer.eval;

import com.legacy.modernizer.eval.metric.ApiCompletenessMetric;
import com.legacy.modernizer.eval.metric.CohesionMetric;
import com.legacy.modernizer.eval.metric.CompilationMetric;
import com.legacy.modernizer.eval.metric.CouplingMetric;
import com.legacy.modernizer.eval.metric.CoverageMetric;
import com.legacy.modernizer.eval.metric.LlmJudgeMetric;
import com.legacy.modernizer.eval.model.EvalMetric;
import com.legacy.modernizer.eval.model.MetricName;
import com.legacy.modernizer.eval.repository.EvalMetricRepository;
import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.AgentTaskRepository;
import com.legacy.modernizer.repository.ArtifactRepository;
import com.legacy.modernizer.repository.MigrationJobRepository;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import com.legacy.modernizer.sharedlib.SharedClassRepository;
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
import java.util.Map;
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

    private final CompilationMetric         compilationMetric;
    private final CoverageMetric            coverageMetric;
    private final ApiCompletenessMetric     apiCompletenessMetric;
    private final LlmJudgeMetric            llmJudgeMetric;
    private final CouplingMetric            couplingMetric;
    private final CohesionMetric            cohesionMetric;
    private final EvalMetricRepository      evalMetricRepository;
    private final ArtifactRepository        artifactRepository;
    private final AgentTaskRepository       taskRepository;
    private final MigrationJobRepository    jobRepository;
    private final ServiceBoundaryRepository boundaryRepository;
    private final SharedClassRepository     sharedClassRepository;

    @Value("${benchmark.results.dir:results}")
    private String resultsDirProp;

    public EvaluatorService(CompilationMetric compilationMetric,
                            CoverageMetric coverageMetric,
                            ApiCompletenessMetric apiCompletenessMetric,
                            LlmJudgeMetric llmJudgeMetric,
                            CouplingMetric couplingMetric,
                            CohesionMetric cohesionMetric,
                            EvalMetricRepository evalMetricRepository,
                            ArtifactRepository artifactRepository,
                            AgentTaskRepository taskRepository,
                            MigrationJobRepository jobRepository,
                            ServiceBoundaryRepository boundaryRepository,
                            SharedClassRepository sharedClassRepository) {
        this.compilationMetric     = compilationMetric;
        this.coverageMetric        = coverageMetric;
        this.apiCompletenessMetric = apiCompletenessMetric;
        this.llmJudgeMetric        = llmJudgeMetric;
        this.couplingMetric        = couplingMetric;
        this.cohesionMetric        = cohesionMetric;
        this.evalMetricRepository  = evalMetricRepository;
        this.artifactRepository    = artifactRepository;
        this.taskRepository        = taskRepository;
        this.jobRepository         = jobRepository;
        this.boundaryRepository    = boundaryRepository;
        this.sharedClassRepository = sharedClassRepository;
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

        // 1. Compilation — post-repair final rate
        CompilationMetric.Result comp = compilationMetric.evaluate(artifacts);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.COMPILATION_SUCCESS,     comp.score(), null, comp.metadata()));
        saved.add(persist(jobId, MULTI_AGENT, MetricName.COMPILATION_POST_REPAIR, comp.score(), null, comp.metadata()));

        // 1b. First-attempt rate — derived from agent_tasks.first_attempt_compiled
        double firstAttemptRate = computeFirstAttemptRate(jobId);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.COMPILATION_FIRST_ATTEMPT,
                firstAttemptRate, null, Map.of("source", "agent_tasks")));

        // 2. Coverage
        CoverageMetric.Result cov = coverageMetric.evaluate(artifacts);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.COVERAGE, cov.score(), null, cov.metadata()));

        // 3. API completeness
        ApiCompletenessMetric.Result api = apiCompletenessMetric.evaluate(originalSrcDir, artifacts);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.API_COMPLETENESS, api.score(), null, api.metadata()));

        // 4. LLM judge — GPT-4o judges Claude-generated multi-agent output
        LlmJudgeMetric.Result judge = llmJudgeMetric.evaluate(artifacts, MULTI_AGENT);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.LLM_JUDGE_SCORE,
                judge.score(), judge.judgeModelId(), judge.metadata()));

        // 5. Shared class duplication rate (DD2) — 0.0 if no commons detected
        double sharedRate = computeSharedClassDuplicationRate(jobId);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.SHARED_CLASS_DUPLICATION_RATE,
                sharedRate, null, Map.of("source", "shared_classes")));

        // 6. Inter-service coupling — cross-boundary CALLS / total CALLS in Neo4j graph
        List<ServiceBoundary> boundaries = boundaryRepository.findByJobId(jobId);
        CouplingMetric.Result coupling = couplingMetric.evaluate(boundaries);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.INTER_SERVICE_COUPLING,
                coupling.score(), null, coupling.metadata()));

        // 7. LCOM4 cohesion — average and perfect-cohesion % across generated classes
        CohesionMetric.Result cohesion = cohesionMetric.evaluate(artifacts);
        saved.add(persist(jobId, MULTI_AGENT, MetricName.AVG_LCOM4,
                cohesion.avgLcom4(), null, cohesion.metadata()));
        saved.add(persist(jobId, MULTI_AGENT, MetricName.PERFECT_COHESION_PCT,
                cohesion.perfectCohesionPct(), null, Map.of("source", "lcom4")));

        log.info("[evaluator] Multi-agent job {} — compile={} cov={} api={} judge={} "
                + "sharedRate={} coupling={} avgLcom4={} perfectCohesionPct={}",
                jobId, comp.score(), cov.score(), api.score(), judge.score(),
                sharedRate, coupling.score(), cohesion.avgLcom4(), cohesion.perfectCohesionPct());
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
        saved.add(persist(jobId, systemId, MetricName.COMPILATION_SUCCESS, comp.score(), null, comp.metadata()));

        // 2. Coverage — attempt if test classes exist in raw response
        CoverageMetric.Result cov = coverageMetric.evaluateBaseline(systemId, rawResponseText);
        saved.add(persist(jobId, systemId, MetricName.COVERAGE, cov.score(), null, cov.metadata()));

        // 3. API completeness — method-name overlap on extracted code, fallback to class names
        ApiCompletenessMetric.Result api =
                apiCompletenessMetric.evaluateBaseline(originalSrcDir, responseJson, systemId, rawResponseText);
        saved.add(persist(jobId, systemId, MetricName.API_COMPLETENESS, api.score(), null, api.metadata()));

        // 4. LLM judge — opposing model judges baseline output
        LlmJudgeMetric.Result judge = llmJudgeMetric.evaluateBaseline(rawResponse, systemId);
        saved.add(persist(jobId, systemId, MetricName.LLM_JUDGE_SCORE,
                judge.score(), judge.judgeModelId(), judge.metadata()));

        // 5. Inter-service coupling — import-reference analysis on extracted code
        CouplingMetric.Result coupling = couplingMetric.evaluateBaseline(responseJson, rawResponseText, systemId);
        saved.add(persist(jobId, systemId, MetricName.INTER_SERVICE_COUPLING,
                coupling.score(), null, coupling.metadata()));

        // 6. LCOM4 cohesion — on extracted Java blocks
        CohesionMetric.Result cohesion = cohesionMetric.evaluateBaseline(rawResponseText, systemId);
        saved.add(persist(jobId, systemId, MetricName.AVG_LCOM4,
                cohesion.avgLcom4(), null, cohesion.metadata()));
        saved.add(persist(jobId, systemId, MetricName.PERFECT_COHESION_PCT,
                cohesion.perfectCohesionPct(), null, Map.of("source", "lcom4")));

        log.info("[evaluator] Baseline {} job {} — compile={} cov={} api={} judge={} coupling={} avgLcom4={}",
                systemId, jobId, comp.score(), cov.score(), api.score(), judge.score(),
                coupling.score(), cohesion.avgLcom4());
        return saved;
    }

    /** Returns all stored eval metrics for a job. */
    @Transactional(readOnly = true)
    public List<EvalMetric> findByJobId(Long jobId) {
        return evalMetricRepository.findByJobId(jobId);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Fraction of classes that were extracted into the commons module.
     * Lower = better after DD2 extraction (less duplication).
     * Returns 0.0 when commons detection has not been run.
     */
    private double computeSharedClassDuplicationRate(Long jobId) {
        int sharedCount = sharedClassRepository.countByJobId(jobId);
        if (sharedCount == 0) return 0.0;
        List<ServiceBoundary> boundaries = boundaryRepository.findByJobId(jobId);
        int total = boundaries.stream()
                .mapToInt(b -> b.getClassFqns() == null ? 0 : b.getClassFqns().size())
                .sum() + sharedCount;
        return total > 0 ? (double) sharedCount / total : 0.0;
    }

    /**
     * Computes the fraction of SERVICE_GEN tasks for this job where the first
     * LLM-generated code compiled without any repair iteration.
     * Returns 0.0 when no tasks with repair tracking data exist (e.g. jobs run
     * before Phase 4.7 was deployed).
     */
    private double computeFirstAttemptRate(Long jobId) {
        List<AgentTask> tasks = taskRepository.findByJobIdAndTaskType(jobId, "SERVICE_GEN");
        List<AgentTask> tracked = tasks.stream()
                .filter(t -> t.getFirstAttemptCompiled() != null)
                .toList();
        if (tracked.isEmpty()) return 0.0;
        long firstOk = tracked.stream().filter(t -> Boolean.TRUE.equals(t.getFirstAttemptCompiled())).count();
        return (double) firstOk / tracked.size();
    }

    private EvalMetric persist(Long jobId, String systemId, MetricName name,
                               double value, String judgeModel,
                               java.util.Map<String, Object> metadata) {
        EvalMetric m = EvalMetric.builder()
                .jobId(jobId)
                .systemId(systemId)
                .metricName(name)
                .metricValue(toBd(value))
                .judgeModel(judgeModel)
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
