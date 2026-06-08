package com.legacy.modernizer.controller;

import com.legacy.modernizer.benchmark.BenchmarkSpec;
import com.legacy.modernizer.eval.EvaluatorService;
import com.legacy.modernizer.eval.model.EvalMetric;
import com.legacy.modernizer.repository.MigrationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * REST endpoints for Phase 4.3 metric computation.
 *
 * <pre>
 * POST /api/eval/{jobId}/multi-agent               — evaluate multi-agent results for a job
 * POST /api/eval/{jobId}/baseline/{systemId}        — evaluate one baseline for a job
 * POST /api/eval/{jobId}/all                        — evaluate multi-agent + both baselines
 * POST /api/eval/all                                — evaluate all repos (uses metrics.json)
 * GET  /api/eval/{jobId}                            — fetch all stored metrics for a job
 * </pre>
 */
@RestController
@RequestMapping("/api/eval")
public class EvaluatorController {

    private static final Logger log = LoggerFactory.getLogger(EvaluatorController.class);

    private final EvaluatorService       evaluatorService;
    private final MigrationJobRepository jobRepository;

    @Value("${benchmark.project.root:#{systemProperties['user.dir'] + '/..'}}")
    private String projectRootProp;

    public EvaluatorController(EvaluatorService evaluatorService,
                                MigrationJobRepository jobRepository) {
        this.evaluatorService = evaluatorService;
        this.jobRepository    = jobRepository;
    }

    /** Run all 4 metrics for the multi-agent system on a job. */
    @PostMapping("/{jobId}/multi-agent")
    public ResponseEntity<?> evaluateMultiAgent(@PathVariable Long jobId) {
        Path srcDir = resolveSrcDir(jobId);
        if (srcDir == null) return jobNotFound(jobId);
        try {
            List<EvalMetric> metrics = evaluatorService.evaluateMultiAgent(jobId, srcDir);
            return ResponseEntity.ok(summaryOf(metrics));
        } catch (Exception e) {
            log.error("[eval-ctrl] multi-agent job {} failed: {}", jobId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Run all 4 metrics for a single-prompt baseline on a job. */
    @PostMapping("/{jobId}/baseline/{systemId}")
    public ResponseEntity<?> evaluateBaseline(@PathVariable Long jobId,
                                              @PathVariable String systemId) {
        if (!systemId.equals("single-prompt-claude") && !systemId.equals("single-prompt-gpt4o")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown systemId: " + systemId,
                    "valid", List.of("single-prompt-claude", "single-prompt-gpt4o")));
        }
        Path srcDir = resolveSrcDir(jobId);
        if (srcDir == null) return jobNotFound(jobId);
        try {
            List<EvalMetric> metrics = evaluatorService.evaluateBaseline(jobId, systemId, srcDir);
            return ResponseEntity.ok(summaryOf(metrics));
        } catch (Exception e) {
            log.error("[eval-ctrl] baseline {} job {} failed: {}", systemId, jobId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Run multi-agent + both baselines for a job. */
    @PostMapping("/{jobId}/all")
    public ResponseEntity<?> evaluateAll(@PathVariable Long jobId) {
        Path srcDir = resolveSrcDir(jobId);
        if (srcDir == null) return jobNotFound(jobId);
        try {
            List<EvalMetric> all = new java.util.ArrayList<>();
            all.addAll(evaluatorService.evaluateMultiAgent(jobId, srcDir));
            all.addAll(evaluatorService.evaluateBaseline(jobId, "single-prompt-claude", srcDir));
            all.addAll(evaluatorService.evaluateBaseline(jobId, "single-prompt-gpt4o", srcDir));
            return ResponseEntity.ok(Map.of("jobId", jobId, "metricsStored", all.size(),
                    "metrics", summaryOf(all)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Evaluate all 10 repos.
     * Reads jobIds from {@code results/{repo}/multi-agent/metrics.json} then runs
     * multi-agent + both baselines for each repo that has a completed benchmark run.
     */
    @PostMapping("/all")
    public ResponseEntity<?> evaluateAllRepos() {
        Path root    = projectRoot();
        Path results = Path.of(
                System.getProperty("benchmark.results.dir", "results"))
                .toAbsolutePath().normalize();

        List<EvalMetric> all = new java.util.ArrayList<>();
        for (BenchmarkSpec spec : BenchmarkSpec.ALL) {
            Path metricsJson = results.resolve(spec.name())
                    .resolve("multi-agent").resolve("metrics.json");
            if (!java.nio.file.Files.exists(metricsJson)) {
                log.warn("[eval-ctrl] No metrics.json for {} — skipping", spec.name());
                continue;
            }
            Long jobId = readJobId(metricsJson);
            if (jobId == null) continue;
            Path srcDir = spec.resolveSrc(root);
            try {
                all.addAll(evaluatorService.evaluateMultiAgent(jobId, srcDir));
                all.addAll(evaluatorService.evaluateBaseline(jobId, "single-prompt-claude", srcDir));
                all.addAll(evaluatorService.evaluateBaseline(jobId, "single-prompt-gpt4o", srcDir));
            } catch (Exception e) {
                log.error("[eval-ctrl] Repo {} failed: {}", spec.name(), e.getMessage(), e);
            }
        }
        return ResponseEntity.ok(Map.of("totalMetricsStored", all.size()));
    }

    /** Fetch all stored eval_metrics rows for a job. */
    @GetMapping("/{jobId}")
    public ResponseEntity<?> getMetrics(@PathVariable Long jobId) {
        return ResponseEntity.ok(evaluatorService.findByJobId(jobId));
    }

    private static Long readJobId(java.nio.file.Path metricsJson) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode n = om.readTree(metricsJson.toFile());
            com.fasterxml.jackson.databind.JsonNode jid = n.get("jobId");
            return jid != null ? jid.asLong() : null;
        } catch (Exception e) { return null; }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Path resolveSrcDir(Long jobId) {
        try {
            String srcDir = jobRepository.findById(jobId)
                    .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId))
                    .getSourceDirectory();
            return Path.of(srcDir);
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    private ResponseEntity<?> jobNotFound(Long jobId) {
        return ResponseEntity.notFound().build();
    }

    private static List<Map<String, Object>> summaryOf(List<EvalMetric> metrics) {
        return metrics.stream().map(m -> Map.<String, Object>of(
                "id",          m.getId(),
                "metricName",  m.getMetricName(),
                "systemId",    m.getSystemId(),
                "metricValue", m.getMetricValue()
        )).toList();
    }

    private Path projectRoot() {
        return Path.of(projectRootProp).toAbsolutePath().normalize();
    }
}
