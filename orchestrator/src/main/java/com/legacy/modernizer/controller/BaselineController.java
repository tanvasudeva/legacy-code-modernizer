package com.legacy.modernizer.controller;

import com.legacy.modernizer.benchmark.BaselineResult;
import com.legacy.modernizer.benchmark.BaselineRunner;
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
 * REST endpoints for Phase 4.2 single-prompt baseline systems.
 *
 * <pre>
 * POST /api/baseline/{repoName}         — run both baselines for one repo
 * POST /api/baseline/{repoName}/gpt4o   — run only the GPT-4o baseline
 * POST /api/baseline/{repoName}/claude  — run only the Claude baseline
 * POST /api/baseline/all                — run both baselines for all 10 repos
 * </pre>
 */
@RestController
@RequestMapping("/api/baseline")
public class BaselineController {

    private static final Logger log = LoggerFactory.getLogger(BaselineController.class);

    private final BaselineRunner      baselineRunner;

    @Value("${benchmark.project.root:#{systemProperties['user.dir'] + '/..'}}")
    private String projectRootProp;

    public BaselineController(BaselineRunner baselineRunner) {
        this.baselineRunner = baselineRunner;
    }

    /** Run both GPT-4o and Claude baselines for a single named repo. */
    @PostMapping("/{repoName}")
    public ResponseEntity<?> runBoth(@PathVariable String repoName) {
        BenchmarkSpec spec = findSpec(repoName);
        if (spec == null) return unknownRepo(repoName);

        log.info("[baseline-ctrl] Running both baselines for {}", repoName);
        List<BaselineResult> results = baselineRunner.runOne(spec, projectRoot());
        long successes = results.stream().filter(BaselineResult::success).count();
        return ResponseEntity.ok(Map.of(
                "repo",      repoName,
                "successes", successes,
                "results",   results
        ));
    }

    /** Run only the GPT-4o baseline for one repo. */
    @PostMapping("/{repoName}/gpt4o")
    public ResponseEntity<?> runGpt4o(@PathVariable String repoName) {
        BenchmarkSpec spec = findSpec(repoName);
        if (spec == null) return unknownRepo(repoName);
        return ResponseEntity.ok(
                baselineRunner.runOne(spec, projectRoot()).stream()
                        .filter(r -> r.system().equals("single-prompt-gpt4o"))
                        .findFirst().orElseThrow());
    }

    /** Run only the Claude baseline for one repo. */
    @PostMapping("/{repoName}/claude")
    public ResponseEntity<?> runClaude(@PathVariable String repoName) {
        BenchmarkSpec spec = findSpec(repoName);
        if (spec == null) return unknownRepo(repoName);
        return ResponseEntity.ok(
                baselineRunner.runOne(spec, projectRoot()).stream()
                        .filter(r -> r.system().equals("single-prompt-claude"))
                        .findFirst().orElseThrow());
    }

    /** Run both baselines for all 10 repos (long-running). */
    @PostMapping("/all")
    public ResponseEntity<?> runAll() {
        log.info("[baseline-ctrl] Running all baselines for {} repos", BenchmarkSpec.ALL.size());
        List<BaselineResult> results = baselineRunner.runAll(projectRoot());
        long successes = results.stream().filter(BaselineResult::success).count();
        return ResponseEntity.ok(Map.of(
                "total",     results.size(),
                "successes", successes,
                "results",   results
        ));
    }

    private BenchmarkSpec findSpec(String name) {
        return BenchmarkSpec.ALL.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    private ResponseEntity<?> unknownRepo(String name) {
        return ResponseEntity.badRequest().body(Map.of(
                "error",     "Unknown repo: " + name,
                "available", BenchmarkSpec.ALL.stream().map(BenchmarkSpec::name).toList()
        ));
    }

    private Path projectRoot() {
        return Path.of(projectRootProp).toAbsolutePath().normalize();
    }
}
