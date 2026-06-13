package com.legacy.modernizer.eval;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.legacy.modernizer.eval.metric.CohesionMetric;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CohesionMetric} and the LCOM4 algorithm.
 * No Spring context, no database, no LLM calls.
 */
class CohesionMetricTest {

    private final CohesionMetric metric = new CohesionMetric();

    // ─────────────────────────────────────────────────────────────────────────
    // LCOM4 algorithm — known fixtures
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void lcom4TwoMethodsSharingNoField() {
        // Two methods, each using a different field → 2 disconnected components → LCOM4 = 2
        ClassOrInterfaceDeclaration cls = parseClass("""
                public class Foo {
                    private String name;
                    private int age;
                    public String getName() { return name; }
                    public int getAge()     { return age;  }
                }
                """);
        assertThat(metric.computeLCOM4(cls)).isEqualTo(2);
    }

    @Test
    void lcom4TwoMethodsSharingOneField() {
        // Both methods touch "value" → single connected component → LCOM4 = 1
        ClassOrInterfaceDeclaration cls = parseClass("""
                public class Counter {
                    private int value;
                    public void increment() { value++; }
                    public int  get()       { return value; }
                }
                """);
        assertThat(metric.computeLCOM4(cls)).isEqualTo(1);
    }

    @Test
    void lcom4SingleMethodIsTriviallyOne() {
        ClassOrInterfaceDeclaration cls = parseClass("""
                public class Solo {
                    private String x;
                    public String getX() { return x; }
                }
                """);
        assertThat(metric.computeLCOM4(cls)).isEqualTo(1);
    }

    @Test
    void lcom4NoMethodsIsTriviallyOne() {
        ClassOrInterfaceDeclaration cls = parseClass("""
                public class Empty {
                    private int n;
                }
                """);
        assertThat(metric.computeLCOM4(cls)).isEqualTo(1);
    }

    @Test
    void lcom4ThreeMethodsTwoConnectedOneIsolated() {
        // m1 and m2 share "a"; m3 uses only "b" → 2 components
        ClassOrInterfaceDeclaration cls = parseClass("""
                public class Mixed {
                    private int a;
                    private int b;
                    public void m1() { a = 1; }
                    public int  m2() { return a; }
                    public void m3() { b = 2; }
                }
                """);
        assertThat(metric.computeLCOM4(cls)).isEqualTo(2);
    }

    @Test
    void lcom4AllMethodsConnectedThroughSharedField() {
        // m1–m2 share "x", m2–m3 share "y" → chain → 1 component
        ClassOrInterfaceDeclaration cls = parseClass("""
                public class Chain {
                    private int x;
                    private int y;
                    public void m1() { x = 1; }
                    public void m2() { x = 2; y = 3; }
                    public void m3() { y++; }
                }
                """);
        assertThat(metric.computeLCOM4(cls)).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // evaluate() — multi-agent artifact list
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void evaluateEmptyArtifactListReturnsZero() {
        CohesionMetric.Result r = metric.evaluate(List.of());
        assertThat(r.classesAnalyzed()).isEqualTo(0);
        assertThat(r.avgLcom4()).isEqualTo(0.0);
    }

    @Test
    void evaluateSkipsNonServiceCodeArtifacts() {
        Artifact test = Artifact.builder()
                .artifactType(ArtifactType.TEST_CODE)
                .content("public class FooTest { private int x; public void a(){x=1;} public void b(){} }")
                .build();
        CohesionMetric.Result r = metric.evaluate(List.of(test));
        assertThat(r.classesAnalyzed()).isEqualTo(0);
    }

    @Test
    void evaluatePerfectlyCoherentArtifact() {
        Artifact a = Artifact.builder()
                .artifactType(ArtifactType.SERVICE_CODE)
                .content("""
                        public class Service {
                            private int count;
                            public void add()  { count++; }
                            public int  get()  { return count; }
                        }
                        """)
                .build();
        CohesionMetric.Result r = metric.evaluate(List.of(a));
        assertThat(r.classesAnalyzed()).isEqualTo(1);
        assertThat(r.avgLcom4()).isEqualTo(1.0);
        assertThat(r.perfectCohesionPct()).isEqualTo(1.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // evaluateBaseline() — raw response text with ```java blocks
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void evaluateBaselineNoJavaBlocksReturnsZero() {
        CohesionMetric.Result r = metric.evaluateBaseline("Here is the plan: {}", "single-prompt-gpt4o");
        assertThat(r.classesAnalyzed()).isEqualTo(0);
    }

    @Test
    void evaluateBaselineExtractsAndScoresLcom4() {
        String raw = """
                Here is the decomposition:
                ```java
                public class UserService {
                    private String name;
                    private int age;
                    public String getName() { return name; }
                    public int getAge()     { return age;  }
                }
                ```
                """;
        CohesionMetric.Result r = metric.evaluateBaseline(raw, "single-prompt-claude");
        assertThat(r.classesAnalyzed()).isEqualTo(1);
        assertThat(r.avgLcom4()).isEqualTo(2.0);   // two disconnected methods
        assertThat(r.perfectCohesionPct()).isEqualTo(0.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UnionFind — internal correctness
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void unionFindSingleNodeIsOneComponent() {
        CohesionMetric.UnionFind uf = new CohesionMetric.UnionFind(1);
        assertThat(uf.countComponents()).isEqualTo(1);
    }

    @Test
    void unionFindNoUnionsEqualsN() {
        CohesionMetric.UnionFind uf = new CohesionMetric.UnionFind(4);
        assertThat(uf.countComponents()).isEqualTo(4);
    }

    @Test
    void unionFindAllUnited() {
        CohesionMetric.UnionFind uf = new CohesionMetric.UnionFind(3);
        uf.union(0, 1);
        uf.union(1, 2);
        assertThat(uf.countComponents()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static ClassOrInterfaceDeclaration parseClass(String src) {
        return StaticJavaParser.parse(src)
                .findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();
    }
}
