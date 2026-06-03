package com.legacy.modernizer.neo4j;

public record IngestionStats(int classNodes, int packageNodes, int relationships) {
    @Override
    public String toString() {
        return "IngestionStats{classNodes=" + classNodes
                + ", packageNodes=" + packageNodes
                + ", relationships=" + relationships + "}";
    }
}
