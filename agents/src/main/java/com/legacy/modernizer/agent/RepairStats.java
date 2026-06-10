package com.legacy.modernizer.agent;

/**
 * Aggregated compilation-repair statistics for one migration job.
 *
 * @param firstAttemptRate      fraction of services that compiled on the first LLM attempt (0–1)
 * @param finalRate             fraction of services that compiled after the full repair loop (0–1)
 * @param avgAttemptsToCompile  mean total compile iterations across all services
 * @param unrepairedCount       services that still failed after all repair attempts
 * @param totalServices         total SERVICE_GEN tasks for the job
 */
public record RepairStats(
        double firstAttemptRate,
        double finalRate,
        double avgAttemptsToCompile,
        int    unrepairedCount,
        int    totalServices
) {}
