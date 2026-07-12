package com.legacy.modernizer.controller;

import com.legacy.modernizer.benchmark.BenchmarkResult;
import com.legacy.modernizer.benchmark.BenchmarkRunner;
import com.legacy.modernizer.benchmark.BenchmarkSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for the Phase 4.1 benchmark runner.
 *
 * <p>{@code POST /api/benchmark/{repoName}} triggers the full multi-agent pipeline
 * for one of the 10 pre-registered benchmark repositories and returns a JSON summary.
 *
 * <p>{@code POST /api/benchmark/all} runs all 10 repos sequentially and returns
 * an aggregate summary — intended for overnight batch runs.
 */
@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkController.class);

    private final BenchmarkRunner benchmarkRunner;

    @Value("${benchmark.project.root:#{systemProperties['user.dir'] + '/..'}}")
    private String projectRootProp;

    public BenchmarkController(BenchmarkRunner benchmarkRunner) {
        this.benchmarkRunner = benchmarkRunner;
    }

    /**
     * Run the full pipeline for a single named benchmark repo.
     *
     * <p>Example: {@code POST /api/benchmark/spring-petclinic}
     */
    /**
     * Run the full pipeline for a single named benchmark repo.
     *
     * <p>Example: {@code POST /api/benchmark/spring-petclinic}
     * <p>Use {@code ?algorithm=leiden} to run with Leiden instead of Louvain for comparison:
     * {@code POST /api/benchmark/spring-petclinic?algorithm=leiden}
     */
    @PostMapping("/{repoName}")
    public ResponseEntity<?> runOne(@PathVariable String repoName,
                                    @RequestParam(required = false) String algorithm) {
        BenchmarkSpec spec = BenchmarkSpec.ALL.stream()
                .filter(s -> s.name().equals(repoName))
                .findFirst()
                .orElse(null);

        if (spec == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown benchmark repo: " + repoName,
                    "available", BenchmarkSpec.ALL.stream().map(BenchmarkSpec::name).toList()
            ));
        }

        if (algorithm != null && !algorithm.equals("louvain") && !algorithm.equals("leiden")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unknown algorithm: " + algorithm,
                    "supported", List.of("louvain", "leiden")
            ));
        }

        log.info("[benchmark-ctrl] Running pipeline for {} (algorithm={})",
                repoName, algorithm != null ? algorithm : "default");
        BenchmarkResult result = benchmarkRunner.run(spec, projectRoot(), algorithm);
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.internalServerError().body(result);
    }

    /**
     * Resume a previously failed pipeline run for an existing job, starting from
     * ArchitectAgent.  The job must have clusterMap data in its metadata (set during
     * the analysis phase) and its RAG chunks must already be indexed.
     *
     * <p>Example: {@code POST /api/benchmark/resume/35}
     */
    @PostMapping("/resume/{jobId}")
    public ResponseEntity<?> resumeJob(@PathVariable Long jobId) {
        log.info("[benchmark-ctrl] Resuming job {} from ArchitectAgent", jobId);
        BenchmarkResult result = benchmarkRunner.resume(jobId, projectRoot());
        return result.success()
                ? ResponseEntity.ok(result)
                : ResponseEntity.internalServerError().body(result);
    }

    /**
     * Run all 10 benchmark repos sequentially.  This is a long-running call
     * (potentially hours if LLM rate limits apply) — use the script-based runner
     * ({@code scripts/run_benchmarks.py}) for production batch runs instead.
     */
    @PostMapping("/all")
    public ResponseEntity<?> runAll() {
        Path root = projectRoot();
        log.info("[benchmark-ctrl] Running full benchmark suite ({} repos)", BenchmarkSpec.ALL.size());

        var results = BenchmarkSpec.ALL.stream()
                .map(spec -> benchmarkRunner.run(spec, root))
                .toList();

        long successes = results.stream().filter(BenchmarkResult::success).count();
        return ResponseEntity.ok(Map.of(
                "total",    results.size(),
                "success",  successes,
                "failed",   results.size() - successes,
                "results",  results
        ));
    }

    /** Lists all registered benchmark repos with their metadata. */
    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(BenchmarkSpec.ALL.stream().map(s -> Map.of(
                "name",      s.name(),
                "srcPath",   s.srcDirectory().toString(),
                "approxLoc", s.approxLoc()
        )).toList());
    }

    private Path projectRoot() {
        return Path.of(projectRootProp).toAbsolutePath().normalize();
    }
}
