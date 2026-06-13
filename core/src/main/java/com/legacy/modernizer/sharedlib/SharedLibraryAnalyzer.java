package com.legacy.modernizer.sharedlib;

import com.legacy.modernizer.model.ServiceBoundary;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DD2 — Shared Library Analyzer.
 *
 * <p>Uses the Neo4j raw Driver (same pattern as {@code GraphIngester}) to:
 * <ol>
 *   <li>Tag Class nodes with a {@code serviceBoundary} property so Cypher can
 *       distinguish callers from different service contexts.</li>
 *   <li>Query cross-service CALLS edges to find classes referenced by callers
 *       from {@code >= minServiceCount} distinct service boundaries.</li>
 *   <li>Clean up the temporary tags after detection.</li>
 * </ol>
 *
 * <p>Designed to run once per job, between ArchitectAgent and ServiceGeneratorAgent.
 */
@Component
public class SharedLibraryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SharedLibraryAnalyzer.class);

    @Value("${sharedLibrary.minServiceCount:2}")
    private int minServiceCount;

    private final Driver driver;

    public SharedLibraryAnalyzer(Driver driver) {
        this.driver = driver;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Tags :Class nodes in Neo4j with a temporary {@code serviceBoundary} property
     * derived from the PostgreSQL ServiceBoundary rows for this job.
     *
     * <p>Uses UNWIND batch merge for efficiency (one round-trip for all assignments).
     */
    public void tagClassesWithService(List<ServiceBoundary> boundaries) {
        List<Map<String, Object>> assignments = new ArrayList<>();
        for (ServiceBoundary b : boundaries) {
            if (b.getClassFqns() == null) continue;
            for (String fqn : b.getClassFqns()) {
                Map<String, Object> a = new HashMap<>();
                a.put("fqn",     fqn);
                a.put("service", b.getServiceName());
                assignments.add(a);
            }
        }
        if (assignments.isEmpty()) {
            log.info("[shared-lib] No class assignments to tag");
            return;
        }
        try (Session session = driver.session()) {
            session.run("""
                    UNWIND $assignments AS a
                    MATCH (c:Class {fqn: a.fqn})
                    SET c.serviceBoundary = a.service
                    """, Map.of("assignments", assignments));
            log.info("[shared-lib] Tagged {} class-service assignments in Neo4j", assignments.size());
        }
    }

    /**
     * Finds classes called by callers from {@code >= minCount} distinct service boundaries.
     *
     * <p>Uses the CALLS relationship built during Neo4j ingestion:
     * {@code (caller:Class)-[:CALLS]->(c:Class)}.
     * A class {@code c} is shared when callers from multiple service contexts depend on it.
     */
    public List<SharedClassResult> findSharedClasses(int minCount) {
        String cypher = """
                MATCH (c:Class)<-[:CALLS]-(a:Class)
                WHERE a.serviceBoundary IS NOT NULL
                WITH c, collect(DISTINCT a.serviceBoundary) AS referencingServices
                WHERE size(referencingServices) >= $minCount
                RETURN c.fqn    AS fqn,
                       c.name   AS shortName,
                       size(referencingServices) AS serviceCount,
                       referencingServices
                ORDER BY serviceCount DESC
                """;

        List<SharedClassResult> results = new ArrayList<>();
        try (Session session = driver.session()) {
            Result result = session.run(cypher, Map.of("minCount", (long) minCount));
            while (result.hasNext()) {
                var record = result.next();
                String fqn       = record.get("fqn").asString("");
                String shortName = record.get("shortName").asString(
                        fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn);
                int count        = record.get("serviceCount").asInt(0);
                List<String> svc = record.get("referencingServices")
                        .asList(v -> v.asString());
                if (!fqn.isBlank()) {
                    results.add(new SharedClassResult(fqn, shortName, count, svc));
                }
            }
        }
        log.info("[shared-lib] Found {} shared classes (minCount={})", results.size(), minCount);
        return results;
    }

    /** Removes the temporary {@code serviceBoundary} property from Class nodes. */
    public void clearServiceTags(List<ServiceBoundary> boundaries) {
        if (boundaries == null || boundaries.isEmpty()) return;
        List<String> fqns = boundaries.stream()
                .filter(b -> b.getClassFqns() != null)
                .flatMap(b -> b.getClassFqns().stream())
                .distinct()
                .toList();
        if (fqns.isEmpty()) return;
        try (Session session = driver.session()) {
            session.run("""
                    UNWIND $fqns AS fqn
                    MATCH (c:Class {fqn: fqn})
                    REMOVE c.serviceBoundary
                    """, Map.of("fqns", fqns));
            log.debug("[shared-lib] Cleared serviceBoundary tags for {} classes", fqns.size());
        }
    }

    public int getMinServiceCount() { return minServiceCount; }
}
