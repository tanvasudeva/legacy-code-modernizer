package com.legacy.modernizer.neo4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.model.CallEdge;
import com.legacy.modernizer.model.ClassNode;
import com.legacy.modernizer.model.DependencyGraph;
import com.legacy.modernizer.model.MethodInfo;
import jakarta.annotation.PostConstruct;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class GraphIngester {

    private static final Logger log = LoggerFactory.getLogger(GraphIngester.class);

    private final Driver driver;
    private final ObjectMapper mapper = new ObjectMapper();

    public GraphIngester(Driver driver) {
        this.driver = driver;
    }

    @PostConstruct
    public void initSchema() {
        try (Session s = driver.session()) {
            s.run("CREATE CONSTRAINT class_fqn IF NOT EXISTS FOR (c:Class)   REQUIRE c.fqn  IS UNIQUE");
            s.run("CREATE CONSTRAINT pkg_name  IF NOT EXISTS FOR (p:Package) REQUIRE p.name IS UNIQUE");
            log.info("Neo4j schema constraints ensured");
        }
    }

    /** Wipes all nodes and relationships from Neo4j. Call before each new repo ingestion. */
    public void clearGraph() {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
            log.info("[graph] Neo4j graph cleared");
        }
    }

    public IngestionStats ingest(DependencyGraph graph) {
        List<ClassNode> nodes = graph.getNodes();
        List<CallEdge>  edges = graph.getEdges();

        Map<String, String> nameToFqn         = buildNameToFqn(nodes);
        Map<String, String> uniqueMethodOwner  = buildUniqueMethodOwner(nodes);

        try (Session session = driver.session()) {
            int classCount = mergeClasses(session, nodes);
            int pkgCount   = mergePackages(session, nodes);
            int relCount   = 0;
            relCount += mergeExtends(session, nodes, nameToFqn);
            relCount += mergeImplements(session, nodes, nameToFqn);
            relCount += mergeImports(session, nodes, nameToFqn);
            relCount += mergeCalls(session, edges, uniqueMethodOwner);

            IngestionStats stats = new IngestionStats(classCount, pkgCount, relCount);
            log.info("Ingestion complete: {}", stats);
            return stats;
        }
    }

    private int mergeClasses(Session session, List<ClassNode> nodes) {
        List<Map<String, Object>> params = nodes.stream().map(n -> {
            Map<String, Object> m = new HashMap<>();
            m.put("fqn",         n.getFullyQualifiedName());
            m.put("name",        n.getClassName());
            m.put("packageName", n.getPackageName());
            m.put("isInterface", n.isInterface());
            m.put("isAbstract",  n.isAbstract());
            return m;
        }).collect(Collectors.toList());

        session.run("""
                UNWIND $nodes AS node
                MERGE (c:Class {fqn: node.fqn})
                SET   c.name        = node.name,
                      c.packageName = node.packageName,
                      c.isInterface = node.isInterface,
                      c.isAbstract  = node.isAbstract
                """, Map.of("nodes", params));
        return nodes.size();
    }

    private int mergePackages(Session session, List<ClassNode> nodes) {
        Set<String> packages = nodes.stream()
                .map(ClassNode::getPackageName)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toSet());

        List<Map<String, Object>> params = nodes.stream()
                .filter(n -> n.getPackageName() != null && !n.getPackageName().isBlank())
                .map(n -> Map.<String, Object>of("fqn", n.getFullyQualifiedName(), "pkg", n.getPackageName()))
                .collect(Collectors.toList());

        session.run("""
                UNWIND $nodes AS node
                MERGE (p:Package {name: node.pkg})
                WITH  p, node
                MATCH (c:Class {fqn: node.fqn})
                MERGE (c)-[:BELONGS_TO]->(p)
                """, Map.of("nodes", params));
        return packages.size();
    }

    private int mergeExtends(Session session, List<ClassNode> nodes, Map<String, String> nameToFqn) {
        List<Map<String, Object>> params = nodes.stream()
                .filter(n -> n.getSuperclass() != null && nameToFqn.containsKey(n.getSuperclass()))
                .map(n -> Map.<String, Object>of(
                        "subFqn", n.getFullyQualifiedName(),
                        "supFqn", nameToFqn.get(n.getSuperclass())))
                .collect(Collectors.toList());

        if (!params.isEmpty()) {
            session.run("""
                    UNWIND $rels AS rel
                    MATCH (sub:Class {fqn: rel.subFqn})
                    MATCH (sup:Class {fqn: rel.supFqn})
                    MERGE (sub)-[:EXTENDS]->(sup)
                    """, Map.of("rels", params));
        }
        return params.size();
    }

    private int mergeImplements(Session session, List<ClassNode> nodes, Map<String, String> nameToFqn) {
        List<Map<String, Object>> params = new ArrayList<>();
        for (ClassNode node : nodes) {
            for (String iface : node.getInterfaces()) {
                if (nameToFqn.containsKey(iface)) {
                    params.add(Map.of("clsFqn", node.getFullyQualifiedName(), "ifcFqn", nameToFqn.get(iface)));
                }
            }
        }
        if (!params.isEmpty()) {
            session.run("""
                    UNWIND $rels AS rel
                    MATCH (cls:Class {fqn: rel.clsFqn})
                    MATCH (ifc:Class {fqn: rel.ifcFqn})
                    MERGE (cls)-[:IMPLEMENTS]->(ifc)
                    """, Map.of("rels", params));
        }
        return params.size();
    }

    private int mergeImports(Session session, List<ClassNode> nodes, Map<String, String> nameToFqn) {
        Set<String> knownFqns = nodes.stream()
                .map(ClassNode::getFullyQualifiedName).collect(Collectors.toSet());

        List<Map<String, Object>> params = new ArrayList<>();
        for (ClassNode node : nodes) {
            for (String imp : node.getImports()) {
                String targetFqn = null;
                if (knownFqns.contains(imp)) {
                    targetFqn = imp;
                } else {
                    String simpleName = imp.contains(".") ? imp.substring(imp.lastIndexOf('.') + 1) : imp;
                    if (nameToFqn.containsKey(simpleName)) targetFqn = nameToFqn.get(simpleName);
                }
                if (targetFqn != null && !targetFqn.equals(node.getFullyQualifiedName())) {
                    params.add(Map.of("srcFqn", node.getFullyQualifiedName(), "tgtFqn", targetFqn));
                }
            }
        }
        if (!params.isEmpty()) {
            session.run("""
                    UNWIND $rels AS rel
                    MATCH (src:Class {fqn: rel.srcFqn})
                    MATCH (tgt:Class {fqn: rel.tgtFqn})
                    MERGE (src)-[:IMPORTS]->(tgt)
                    """, Map.of("rels", params));
        }
        return params.size();
    }

    private int mergeCalls(Session session, List<CallEdge> edges, Map<String, String> uniqueMethodOwner) {
        List<Map<String, Object>> params = new ArrayList<>();
        for (CallEdge edge : edges) {
            String calleeFqn = uniqueMethodOwner.get(edge.getCalleeMethod());
            if (calleeFqn == null || calleeFqn.equals(edge.getCallerClass())) continue;
            params.add(Map.of("callerFqn", edge.getCallerClass(), "calleeFqn", calleeFqn));
        }
        if (!params.isEmpty()) {
            session.run("""
                    UNWIND $rels AS rel
                    MATCH (caller:Class {fqn: rel.callerFqn})
                    MATCH (callee:Class {fqn: rel.calleeFqn})
                    MERGE (caller)-[:CALLS]->(callee)
                    """, Map.of("rels", params));
        }
        return params.size();
    }

    public String exportAdjacencyListJson() {
        List<Map<String, String>> edgeList = new ArrayList<>();
        try (Session session = driver.session()) {
            session.run("MATCH (src:Class)-[r]->(tgt:Class) RETURN src.fqn AS source, type(r) AS type, tgt.fqn AS target")
                    .list().forEach(record -> edgeList.add(Map.of(
                            "source", record.get("source").asString(),
                            "target", record.get("target").asString(),
                            "type",   record.get("type").asString())));
        }
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(edgeList);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise adjacency list", e);
        }
    }

    /**
     * Returns cross-cluster CALLS edge counts: fromCluster → toCluster → count.
     * Only cross-cluster edges are included (same-cluster edges are omitted).
     */
    public Map<String, Map<String, Integer>> computeInterClusterEdges(Map<String, String> clusterMap) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        try (Session session = driver.session()) {
            session.run("MATCH (a:Class)-[:CALLS]->(b:Class) RETURN a.fqn AS fromFqn, b.fqn AS toFqn")
                    .list().forEach(row -> {
                        String fromFqn     = row.get("fromFqn").asString();
                        String toFqn       = row.get("toFqn").asString();
                        String fromCluster = clusterMap.get(fromFqn);
                        String toCluster   = clusterMap.get(toFqn);
                        if (fromCluster == null || toCluster == null
                                || fromCluster.equals(toCluster)) return;
                        result.computeIfAbsent(fromCluster, k -> new LinkedHashMap<>())
                              .merge(toCluster, 1, Integer::sum);
                    });
        } catch (Exception e) {
            log.warn("[graph] computeInterClusterEdges failed: {}", e.getMessage());
        }
        return result;
    }

    private Map<String, String> buildNameToFqn(List<ClassNode> nodes) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ClassNode n : nodes) map.putIfAbsent(n.getClassName(), n.getFullyQualifiedName());
        return map;
    }

    private Map<String, String> buildUniqueMethodOwner(List<ClassNode> nodes) {
        Map<String, String> unique    = new LinkedHashMap<>();
        Set<String>         ambiguous = new HashSet<>();
        for (ClassNode node : nodes) {
            for (MethodInfo m : node.getMethods()) {
                String name = m.getName();
                if (ambiguous.contains(name)) continue;
                if (unique.containsKey(name)) { ambiguous.add(name); unique.remove(name); }
                else unique.put(name, node.getFullyQualifiedName());
            }
        }
        return unique;
    }
}
