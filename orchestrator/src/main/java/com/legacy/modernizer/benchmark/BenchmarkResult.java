package com.legacy.modernizer.benchmark;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of one full pipeline run on a single benchmark repository.
 *
 * <p>Persisted to {@code results/{repoName}/multi-agent/metrics.json} by
 * {@link BenchmarkRunner} for later aggregation across all 10 repos.
 */
public record BenchmarkResult(
        String        repoName,
        Long          jobId,
        int           serviceCount,
        int           totalFilesGenerated,
        int           totalTokensUsed,
        long          elapsedMs,
        boolean       bundleAssembled,
        String        bundlePath,
        List<String>  serviceNames,
        String        errorMessage,
        LocalDateTime completedAt
) {
    public boolean success() { return errorMessage == null; }
}
