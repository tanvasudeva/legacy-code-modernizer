package com.legacy.modernizer.eval.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.legacy.modernizer.model.ServiceBoundary;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inter-service coupling metric (lower is better).
 *
 * <p><b>Multi-agent path:</b> tags Class nodes in Neo4j with their service boundary,
 * counts CALLS edges that cross service boundaries, then clears the tags.
 * Score = cross_service_calls / total_calls (0.0–1.0).
 *
 * <p><b>Baseline path:</b> parses Java blocks from the raw LLM response, maps each
 * class to a service via {@code response.json}, and counts cross-service import
 * references as a proxy for coupling.
 */
@Component
public class CouplingMetric {

    private static final Logger log = LoggerFactory.getLogger(CouplingMetric.class);

    private static final Pattern JAVA_BLOCK = Pattern.compile(
            "```java\\s*\\n(.*?)```", Pattern.DOTALL);

    private final Driver driver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CouplingMetric(Driver driver) {
        this.driver = driver;
    }

    public record Result(double score, long crossCalls, long totalCalls, Map<String, Object> metadata) {}

    // ─── Multi-agent (Neo4j graph) ────────────────────────────────────────────

    /**
     * Tags each Class node with its service boundary, counts cross-service CALLS
     * edges, then removes the temporary tags.
     */
    public Result evaluate(List<ServiceBoundary> boundaries) {
        if (boundaries == null || boundaries.size() < 2) {
            return new Result(0.0, 0, 0,
                    Map.of("reason", "fewer than 2 service boundaries — coupling undefined"));
        }

        try (Session session = driver.session()) {
            try {
                // Tag each class node with its service boundary
                for (ServiceBoundary b : boundaries) {
                    if (b.getClassFqns() == null || b.getClassFqns().isEmpty()) continue;
                    session.run("""
                            UNWIND $fqns AS fqn
                            MATCH (c:Class {fqn: fqn})
                            SET c.serviceBoundary = $svc
                            """, Map.of("fqns", b.getClassFqns(), "svc", b.getServiceName()));
                }

                long totalCalls = session.run("""
                        MATCH (a:Class)-[:CALLS]->(b:Class)
                        WHERE a.serviceBoundary IS NOT NULL AND b.serviceBoundary IS NOT NULL
                        RETURN count(*) AS total
                        """).single().get("total").asLong();

                if (totalCalls == 0) {
                    return new Result(0.0, 0, 0,
                            Map.of("reason", "no CALLS edges found between tagged classes"));
                }

                long crossCalls = session.run("""
                        MATCH (a:Class)-[:CALLS]->(b:Class)
                        WHERE a.serviceBoundary IS NOT NULL
                          AND b.serviceBoundary IS NOT NULL
                          AND a.serviceBoundary <> b.serviceBoundary
                        RETURN count(*) AS crossCalls
                        """).single().get("crossCalls").asLong();

                double score = (double) crossCalls / totalCalls;
                log.info("[coupling] crossCalls={} totalCalls={} score={}", crossCalls, totalCalls, score);

                return new Result(score, crossCalls, totalCalls, Map.of(
                        "crossServiceCalls", crossCalls,
                        "totalCalls",        totalCalls,
                        "serviceCount",      boundaries.size()
                ));

            } finally {
                session.run("MATCH (c:Class) WHERE c.serviceBoundary IS NOT NULL REMOVE c.serviceBoundary");
            }
        } catch (Exception e) {
            log.warn("[coupling] Neo4j query failed: {}", e.getMessage());
            return new Result(0.0, 0, 0, Map.of("error", e.getMessage()));
        }
    }

    // ─── Baseline (import-reference analysis) ────────────────────────────────

    /**
     * Estimates coupling for a baseline by counting cross-service import references
     * in extracted Java blocks. Requires {@code response.json} to map classes to
     * services.
     */
    public Result evaluateBaseline(Path responseJsonPath, String rawResponseText, String systemId) {
        Map<String, String> classToService = parseClassToServiceMap(responseJsonPath);
        if (classToService.isEmpty()) {
            log.info("[coupling][{}] No class→service mapping in response.json", systemId);
            return new Result(0.0, 0, 0,
                    Map.of("reason", "no response.json plan available", "system", systemId));
        }

        if (rawResponseText == null || rawResponseText.isBlank()) {
            return new Result(0.0, 0, 0,
                    Map.of("reason", "no raw response text", "system", systemId));
        }

        long totalRefs = 0;
        long crossRefs = 0;
        Matcher m = JAVA_BLOCK.matcher(rawResponseText);

        while (m.find()) {
            String src = m.group(1).strip();
            if (src.isBlank()) continue;
            try {
                CompilationUnit cu = StaticJavaParser.parse(src);

                String pkg      = cu.getPackageDeclaration()
                        .map(p -> p.getNameAsString()).orElse("");
                String typeName = cu.getTypes().isEmpty()
                        ? "" : cu.getTypes().get(0).getNameAsString();
                String fqn      = pkg.isEmpty() ? typeName : pkg + "." + typeName;
                String ownerSvc = classToService.get(fqn);
                if (ownerSvc == null) continue;

                for (ImportDeclaration imp : cu.getImports()) {
                    if (imp.isAsterisk() || imp.isStatic()) continue;
                    String importedSvc = classToService.get(imp.getNameAsString());
                    if (importedSvc == null) continue;
                    totalRefs++;
                    if (!importedSvc.equals(ownerSvc)) crossRefs++;
                }
            } catch (ParseProblemException ignored) {}
        }

        if (totalRefs == 0) {
            return new Result(0.0, 0, 0,
                    Map.of("reason", "no resolvable cross-class imports found", "system", systemId));
        }

        double score = (double) crossRefs / totalRefs;
        log.info("[coupling][{}] crossRefs={} totalRefs={} score={}", systemId, crossRefs, totalRefs, score);

        return new Result(score, crossRefs, totalRefs, Map.of(
                "crossServiceRefs", crossRefs,
                "totalRefs",        totalRefs,
                "mode",             "import-reference",
                "system",           systemId
        ));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Map<String, String> parseClassToServiceMap(Path responseJsonPath) {
        Map<String, String> map = new HashMap<>();
        if (responseJsonPath == null || !Files.exists(responseJsonPath)) return map;
        try {
            JsonNode root = objectMapper.readTree(responseJsonPath.toFile());
            if (!root.isArray()) return map;
            for (JsonNode svc : root) {
                String svcName = svc.path("serviceName").asText("");
                JsonNode fqns  = svc.get("classFqns");
                if (fqns == null || !fqns.isArray() || svcName.isBlank()) continue;
                fqns.forEach(n -> map.put(n.asText(), svcName));
            }
        } catch (Exception e) {
            log.warn("[coupling] Cannot parse response.json at {}: {}", responseJsonPath, e.getMessage());
        }
        return map;
    }
}
