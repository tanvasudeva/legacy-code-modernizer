package com.legacy.modernizer.bundle;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Summary returned by {@link BundleAssembler#assemble} describing what was packaged.
 */
public record BundleManifest(
        Long          jobId,
        List<String>  serviceNames,
        int           fileCount,
        LocalDateTime assembledAt
) {}
