package com.legacy.modernizer.sharedlib;

import java.util.List;

/**
 * Result of shared-library detection for a migration job.
 *
 * <p>Returned by {@link SharedLibraryDetector#detect} and exposed via
 * {@code GET /api/jobs/{id}/shared-classes}.
 *
 * @param jobId               migration job ID
 * @param commonsModuleName   generated module name, e.g. "spring-petclinic-commons"
 * @param sharedClasses       one entry per detected cross-service shared class
 * @param totalSharedClasses  convenience count of sharedClasses list
 * @param duplicationsAvoided sum of (serviceCount - 1) per shared class — how many
 *                            copy-paste instances were prevented
 * @param duplicationRate     sharedClassCount / totalClassCount — fraction of all
 *                            classes that needed deduplication (lower is better after extraction)
 */
public record SharedLibraryPlan(
        Long jobId,
        String commonsModuleName,
        List<SharedClassResult> sharedClasses,
        int totalSharedClasses,
        int duplicationsAvoided,
        double duplicationRate
) {}
