package com.legacy.modernizer.dto;

import java.util.Map;

public record AnalysisResult(
        Long jobId,
        String status,
        int classNodes,
        int packageNodes,
        int relationships,
        int clusterCount,
        Map<String, String> clusterMap
) {}
