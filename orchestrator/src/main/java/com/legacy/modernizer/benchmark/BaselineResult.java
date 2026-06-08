package com.legacy.modernizer.benchmark;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Result of one single-prompt baseline run.
 * Written as {@code metadata.json} alongside {@code response_raw.txt} and
 * {@code response.json} in {@code results/{repoName}/{system}/}.
 */
public record BaselineResult(
        String        repoName,
        String        system,            // "single-prompt-gpt4o" | "single-prompt-claude"
        String        modelId,
        boolean       success,
        String        rawResponse,
        int           filesIncluded,
        int           filesSkipped,
        int           charsSent,
        Integer       tokensUsed,
        long          elapsedMs,
        int           servicesExtracted, // number of JSON objects in parsed response (-1 if unparseable)
        String        errorMessage,
        LocalDateTime completedAt
) {}
