package com.legacy.modernizer.sharedlib;

import java.util.List;

/**
 * One cross-service shared class detected by Neo4j CALLS analysis.
 *
 * @param fqn                fully-qualified class name (e.g. "org.petclinic.BaseEntity")
 * @param shortName          simple class name (last segment)
 * @param serviceCount       how many distinct service boundaries call/use this class
 * @param referencingServices service names that reference this class via CALLS edges
 */
public record SharedClassResult(
        String fqn,
        String shortName,
        int serviceCount,
        List<String> referencingServices
) {}
