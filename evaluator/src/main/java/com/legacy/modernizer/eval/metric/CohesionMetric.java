package com.legacy.modernizer.eval.metric;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NameExpr;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LCOM4 (Lack of Cohesion of Methods 4) cohesion metric.
 *
 * <p>Algorithm per class:
 * <ol>
 *   <li>Collect all field names declared in the class.</li>
 *   <li>For each method, record which declared fields it references.</li>
 *   <li>Build an adjacency graph: two methods are joined when they share ≥ 1 field.</li>
 *   <li>Count connected components via Union-Find — that count is the LCOM4 value.</li>
 * </ol>
 *
 * <p>LCOM4 = 1 means perfectly cohesive; > 1 means the class is a split candidate.
 * The job-level result reports the average and the fraction of classes with LCOM4 = 1.
 */
@Component
public class CohesionMetric {

    private static final Logger log = LoggerFactory.getLogger(CohesionMetric.class);

    private static final Pattern JAVA_BLOCK = Pattern.compile(
            "```java\\s*\\n(.*?)```", Pattern.DOTALL);

    public record Result(
            double avgLcom4,
            double perfectCohesionPct,
            int    classesAnalyzed,
            Map<String, Object> metadata
    ) {}

    // ─── Multi-agent ──────────────────────────────────────────────────────────

    public Result evaluate(List<Artifact> artifacts) {
        List<Integer> scores = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (a.getArtifactType() != ArtifactType.SERVICE_CODE) continue;
            if (a.getContent() == null || a.getContent().isBlank()) continue;
            try {
                collectLcom4(StaticJavaParser.parse(a.getContent()), scores);
            } catch (ParseProblemException e) {
                log.debug("[cohesion] Parse error in {}: {}", a.getFilePath(), e.getMessage());
            }
        }
        log.info("[cohesion] multi-agent: {} classes analyzed", scores.size());
        return buildResult(scores);
    }

    // ─── Baseline ─────────────────────────────────────────────────────────────

    public Result evaluateBaseline(String rawResponseText, String systemId) {
        if (rawResponseText == null || rawResponseText.isBlank()) {
            return new Result(0.0, 0.0, 0,
                    Map.of("reason", "no raw response text", "system", systemId));
        }
        List<Integer> scores = new ArrayList<>();
        Matcher m = JAVA_BLOCK.matcher(rawResponseText);
        while (m.find()) {
            String src = m.group(1).strip();
            if (src.isBlank()) continue;
            try {
                collectLcom4(StaticJavaParser.parse(src), scores);
            } catch (ParseProblemException ignored) {}
        }
        log.info("[cohesion][{}] {} classes analyzed", systemId, scores.size());
        return buildResult(scores);
    }

    // ─── LCOM4 core ──────────────────────────────────────────────────────────

    void collectLcom4(CompilationUnit cu, List<Integer> scores) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
            if (!cls.isInterface()) scores.add(computeLCOM4(cls));
        });
    }

    /**
     * Computes LCOM4 for a single class. Returns 1 for classes with 0 or 1 methods
     * (trivially cohesive by convention).
     */
    public int computeLCOM4(ClassOrInterfaceDeclaration cls) {
        Set<String> fieldNames = extractFieldNames(cls);
        List<MethodDeclaration> methods = cls.getMethods();
        if (methods.size() <= 1) return 1;

        // Stable unique key per method: name + source position
        List<String> keys = new ArrayList<>();
        Map<String, Set<String>> methodFields = new LinkedHashMap<>();
        for (MethodDeclaration m : methods) {
            String key = m.getNameAsString() + "@" + m.getBegin().map(Object::toString).orElse("?");
            keys.add(key);
            methodFields.put(key, referencedFields(m, fieldNames));
        }

        int n = keys.size();
        UnionFind uf = new UnionFind(n);
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!Collections.disjoint(methodFields.get(keys.get(i)),
                                          methodFields.get(keys.get(j)))) {
                    uf.union(i, j);
                }
            }
        }
        return uf.countComponents();
    }

    private Set<String> extractFieldNames(ClassOrInterfaceDeclaration cls) {
        Set<String> names = new HashSet<>();
        for (FieldDeclaration f : cls.getFields()) {
            f.getVariables().forEach(v -> names.add(v.getNameAsString()));
        }
        return names;
    }

    private Set<String> referencedFields(MethodDeclaration method, Set<String> fieldNames) {
        Set<String> refs = new HashSet<>();
        method.findAll(NameExpr.class)
              .forEach(n -> { if (fieldNames.contains(n.getNameAsString())) refs.add(n.getNameAsString()); });
        return refs;
    }

    private Result buildResult(List<Integer> scores) {
        if (scores.isEmpty()) {
            return new Result(0.0, 0.0, 0, Map.of("reason", "no parseable classes found"));
        }
        double avg    = scores.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        long   perfect = scores.stream().filter(s -> s == 1).count();
        double pct    = (double) perfect / scores.size();

        return new Result(avg, pct, scores.size(), Map.of(
                "classesAnalyzed", scores.size(),
                "avgLcom4",        avg,
                "perfectCount",    (int) perfect,
                "perfectPct",      pct,
                "distribution",    scoreDistribution(scores)
        ));
    }

    private Map<String, Integer> scoreDistribution(List<Integer> scores) {
        Map<String, Integer> dist = new TreeMap<>();
        scores.forEach(s -> dist.merge("lcom4_" + s, 1, Integer::sum));
        return dist;
    }

    // ─── Union-Find ───────────────────────────────────────────────────────────

    public static class UnionFind {
        private final int[] parent, rank;

        public UnionFind(int n) {
            parent = new int[n];
            rank   = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;
        }

        public int find(int x) {
            if (parent[x] != x) parent[x] = find(parent[x]);
            return parent[x];
        }

        public void union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra == rb) return;
            if (rank[ra] < rank[rb])      parent[ra] = rb;
            else if (rank[ra] > rank[rb]) parent[rb] = ra;
            else { parent[rb] = ra; rank[ra]++; }
        }

        public int countComponents() {
            Set<Integer> roots = new HashSet<>();
            for (int i = 0; i < parent.length; i++) roots.add(find(i));
            return roots.size();
        }
    }
}
