package com.legacy.modernizer.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 4.2 — Baseline runner.
 *
 * <p>Runs both single-prompt baselines ({@link SinglePromptGpt4o} and
 * {@link SinglePromptClaude}) on a list of {@link BenchmarkSpec} repositories.
 * Results are written to {@code results/{repoName}/{system}/} and a consolidated
 * summary is saved to {@code results/baseline_summary.json}.
 */
@Component
public class BaselineRunner {

    private static final Logger log = LoggerFactory.getLogger(BaselineRunner.class);

    private final SinglePromptGpt4o  gpt4oBaseline;
    private final SinglePromptClaude claudeBaseline;

    @Value("${benchmark.results.dir:results}")
    private String resultsDirProp;

    public BaselineRunner(SinglePromptGpt4o gpt4oBaseline,
                          SinglePromptClaude claudeBaseline) {
        this.gpt4oBaseline  = gpt4oBaseline;
        this.claudeBaseline = claudeBaseline;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Runs both baselines for a single repository and returns both results.
     */
    public List<BaselineResult> runOne(BenchmarkSpec spec, Path projectRoot) {
        Path resultsRoot = Path.of(resultsDirProp).toAbsolutePath().normalize();
        List<BaselineResult> results = new ArrayList<>();
        results.add(gpt4oBaseline.run(spec, projectRoot, resultsRoot));
        results.add(claudeBaseline.run(spec, projectRoot, resultsRoot));
        return results;
    }

    /**
     * Runs both baselines for all 10 benchmark repositories and writes a
     * consolidated {@code results/baseline_summary.json}.
     *
     * @return flat list of all results (2 per repo × 10 repos = 20 total)
     */
    public List<BaselineResult> runAll(Path projectRoot) {
        Path resultsRoot = Path.of(resultsDirProp).toAbsolutePath().normalize();
        List<BaselineResult> allResults = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        for (BenchmarkSpec spec : BenchmarkSpec.ALL) {
            log.info("[baseline-runner] {} — starting both baselines", spec.name());
            allResults.add(gpt4oBaseline.run(spec, projectRoot, resultsRoot));
            allResults.add(claudeBaseline.run(spec, projectRoot, resultsRoot));
        }

        writeSummary(resultsRoot, allResults, System.currentTimeMillis() - totalStart);
        return allResults;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void writeSummary(Path resultsRoot, List<BaselineResult> results, long totalMs) {
        long gpt4oSuccess  = results.stream()
                .filter(r -> r.system().equals("single-prompt-gpt4o") && r.success()).count();
        long claudeSuccess = results.stream()
                .filter(r -> r.system().equals("single-prompt-claude") && r.success()).count();

        List<Map<String, Object>> rows = results.stream().map(r -> Map.<String, Object>of(
                "repo",              r.repoName(),
                "system",            r.system(),
                "success",           r.success(),
                "servicesExtracted", r.servicesExtracted(),
                "filesIncluded",     r.filesIncluded(),
                "charsSent",         r.charsSent(),
                "tokensUsed",        r.tokensUsed() != null ? r.tokensUsed() : -1,
                "elapsedMs",         r.elapsedMs(),
                "errorMessage",      r.errorMessage() != null ? r.errorMessage() : ""
        )).toList();

        Map<String, Object> summary = Map.of(
                "runAt",            LocalDateTime.now().toString(),
                "totalRuns",        results.size(),
                "gpt4oSucceeded",   gpt4oSuccess,
                "claudeSucceeded",  claudeSuccess,
                "totalElapsedMs",   totalMs,
                "results",          rows
        );

        try {
            ObjectMapper om = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            om.writerWithDefaultPrettyPrinter()
              .writeValue(resultsRoot.resolve("baseline_summary.json").toFile(), summary);
            log.info("[baseline-runner] Summary written → {}",
                    resultsRoot.resolve("baseline_summary.json"));
        } catch (IOException e) {
            log.warn("[baseline-runner] Could not write summary: {}", e.getMessage());
        }
    }
}
