package com.legacy.modernizer.eval;

import com.legacy.modernizer.eval.metric.ApiCompletenessMetric;
import com.legacy.modernizer.eval.metric.BaselineCodeExtractor;
import com.legacy.modernizer.eval.metric.CompilationMetric;
import com.legacy.modernizer.eval.metric.CoverageMetric;
import com.legacy.modernizer.eval.metric.LlmJudgeMetric;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for all four Phase 4.3/4.5 metric classes.
 * No Spring context, no database, no LLM calls.
 */
class MetricTest {

    // shared extractor instance (no Spring needed)
    private final BaselineCodeExtractor extractor = new BaselineCodeExtractor();

    // ─────────────────────────────────────────────────────────────────────────
    // CompilationMetric
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void compilationBaselineWithNoCodeBlocksReturnsZero() {
        CompilationMetric m = new CompilationMetric(extractor);
        // Response that has no ```java blocks → no code to compile → 0.0
        CompilationMetric.Result r = m.evaluateBaseline(
                "single-prompt-gpt4o",
                "Here is my decomposition plan: [{}]. No code provided.");
        assertThat(r.score()).isEqualTo(0.0);
        assertThat(r.servicesTotal()).isEqualTo(0);
    }

    @Test
    void compilationNoArtifactsReturnsZero() {
        CompilationMetric m = new CompilationMetric(extractor);
        CompilationMetric.Result r = m.evaluate(List.of());
        assertThat(r.score()).isEqualTo(0.0);
    }

    @Test
    void compilationArtifactsWithNullPathSkipped() {
        CompilationMetric m = new CompilationMetric(extractor);
        Artifact a = Artifact.builder()
                .artifactType(ArtifactType.SERVICE_CODE)
                .classFqn("owner-service")
                .filePath(null)
                .content("class X{}")
                .build();
        CompilationMetric.Result r = m.evaluate(List.of(a));
        assertThat(r.score()).isEqualTo(0.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CoverageMetric
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void coverageBaselineWithNoTestsReturnsGenuineZero() {
        CoverageMetric m = new CoverageMetric(extractor);
        // No @Test or JUnit in response → genuine 0.0 (not a fake)
        CoverageMetric.Result r = m.evaluateBaseline(
                "single-prompt-claude", "Plain text, no java blocks.");
        assertThat(r.score()).isEqualTo(0.0);
        assertThat(r.metadata()).containsKey("reason");
    }

    @Test
    void coverageParseLineCoverage(@TempDir Path tmp) throws Exception {
        // Minimal jacoco.xml with known line coverage
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="test">
                  <counter type="INSTRUCTION" missed="10" covered="90"/>
                  <counter type="LINE" missed="20" covered="80"/>
                  <counter type="COMPLEXITY" missed="5" covered="15"/>
                </report>
                """;
        Path xmlFile = tmp.resolve("jacoco.xml");
        Files.writeString(xmlFile, xml);

        double cov = CoverageMetric.parseLineCoverage(xmlFile);
        // covered=80, missed=20 → 80/100 = 0.8
        assertThat(cov).isEqualTo(0.8);
    }

    @Test
    void coverageParseLineCoverageZeroTotal(@TempDir Path tmp) throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="test">
                  <counter type="LINE" missed="0" covered="0"/>
                </report>
                """;
        Path xmlFile = tmp.resolve("jacoco.xml");
        Files.writeString(xmlFile, xml);

        assertThat(CoverageMetric.parseLineCoverage(xmlFile)).isEqualTo(0.0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ApiCompletenessMetric
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void apiCompletenessExtractionFromSourceDir(@TempDir Path src) throws IOException {
        Files.writeString(src.resolve("Foo.java"), """
                public class Foo {
                    public void doSomething() {}
                    public String getValue() { return "x"; }
                    private void helper() {}
                }
                """);
        ApiCompletenessMetric m = new ApiCompletenessMetric();
        var names = m.extractPublicMethodNames(src);
        assertThat(names).containsExactlyInAnyOrder("doSomething", "getValue");
        assertThat(names).doesNotContain("helper");
    }

    @Test
    void apiCompletenessExtractionFromArtifacts() {
        Artifact a = Artifact.builder()
                .artifactType(ArtifactType.SERVICE_CODE)
                .content("""
                        public class OwnerService {
                            public java.util.List<Object> findAll() { return null; }
                            public void delete(Long id) {}
                        }
                        """)
                .build();
        ApiCompletenessMetric m = new ApiCompletenessMetric();
        var names = m.extractMethodNamesFromArtifacts(List.of(a));
        assertThat(names).containsExactlyInAnyOrder("findAll", "delete");
    }

    @Test
    void apiCompletenessScoreFullOverlap(@TempDir Path src) throws IOException {
        Files.writeString(src.resolve("Svc.java"), "public class Svc { public void run() {} }");
        Artifact a = Artifact.builder()
                .artifactType(ArtifactType.SERVICE_CODE)
                .content("public class Svc { public void run() {} }")
                .build();
        ApiCompletenessMetric m = new ApiCompletenessMetric();
        var result = m.evaluate(src, List.of(a));
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void apiCompletenessScoreNoOverlap(@TempDir Path src) throws IOException {
        Files.writeString(src.resolve("A.java"), "public class A { public void foo() {} }");
        Artifact a = Artifact.builder()
                .artifactType(ArtifactType.SERVICE_CODE)
                .content("public class B { public void bar() {} }")
                .build();
        ApiCompletenessMetric m = new ApiCompletenessMetric();
        var result = m.evaluate(src, List.of(a));
        assertThat(result.score()).isEqualTo(0.0);
    }

    @Test
    void apiCompletenessBaselineFallbackToClassNames(@TempDir Path src,
                                                      @TempDir Path resultsDir) throws IOException {
        Files.writeString(src.resolve("Owner.java"), "class Owner {}");
        Files.writeString(src.resolve("Pet.java"),   "class Pet {}");

        Path responseJson = resultsDir.resolve("response.json");
        Files.writeString(responseJson, """
                [{"serviceName":"owner-service","classFqns":["com.example.Owner"]}]
                """);

        ApiCompletenessMetric m = new ApiCompletenessMetric();
        // rawResponseText has no ```java blocks → triggers class-name fallback
        var result = m.evaluateBaseline(src, responseJson, "single-prompt-gpt4o",
                "Plain text response with no code blocks.");

        // class-name fallback: Owner matched, Pet not matched → 1/2 = 0.5
        assertThat(result.score()).isEqualTo(0.5);
        assertThat(result.metadata()).containsEntry("mode", "class-name-fallback");
    }

    @Test
    void apiCompletenessBaselineUsesCodeBlocksWhenPresent(@TempDir Path src) throws IOException {
        // Original has public method "doWork"
        Files.writeString(src.resolve("SomeService.java"),
                "public class SomeService { public void doWork() {} }");

        ApiCompletenessMetric m = new ApiCompletenessMetric();
        // Response contains a java block that also has "doWork" → method-level match
        String rawWithCode = """
                ```java
                package com.example;
                public class SomeService {
                    public void doWork() {}
                }
                ```
                """;
        var result = m.evaluateBaseline(src, null, "single-prompt-gpt4o", rawWithCode);

        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.metadata()).containsEntry("mode", "code-block-method-overlap");
    }

    @Test
    void toSimpleNamesStripsPackage() {
        var simple = ApiCompletenessMetric.toSimpleNames(
                java.util.Set.of("com.example.Owner", "org.petclinic.Vet"));
        assertThat(simple).containsExactlyInAnyOrder("Owner", "Vet");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LlmJudgeMetric — parsing helpers only (no LLM calls)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void parseSubScoresValidJson() {
        // parseSubScoresStatic is a static helper — no model needed
        Map<String, Object> scores = LlmJudgeMetric.parseSubScoresStatic(
                "{\"correctness\":8,\"readability\":7,\"idiomaticity\":9,\"completeness\":8,\"dry\":7}");
        assertThat(scores).containsEntry("correctness", 8.0)
                          .containsEntry("dry", 7.0);
    }

    @Test
    void parseSubScoresStripsCodeFence() {
        Map<String, Object> scores = LlmJudgeMetric.parseSubScoresStatic(
                "```json\n{\"correctness\":6,\"readability\":6,\"idiomaticity\":6,\"completeness\":6,\"dry\":6}\n```");
        assertThat(scores).hasSize(5);
    }

    @Test
    void parseSubScoresInvalidJsonReturnsEmpty() {
        assertThat(LlmJudgeMetric.parseSubScoresStatic("not json at all")).isEmpty();
    }

    @Test
    void computeAverageCorrect() {
        assertThat(LlmJudgeMetric.computeAverage(
                Map.of("a", 8.0, "b", 6.0, "c", 10.0, "d", 7.0, "e", 9.0)))
                .isEqualTo(8.0);
    }

    @Test
    void computeAverageEmptyReturnsZero() {
        assertThat(LlmJudgeMetric.computeAverage(Map.of())).isEqualTo(0.0);
    }
}
