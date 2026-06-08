package com.legacy.modernizer.benchmark;

import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SinglePromptBaseline helper logic and SourceCollector.
 * No Spring context, no LLM calls, no database.
 */
class BaselineTest {

    // ─── SinglePromptBaseline.extractJson ────────────────────────────────────

    @Test
    void extractJsonFromCleanArray() {
        String raw = "[{\"serviceName\":\"owner-service\"}]";
        assertThat(SinglePromptBaseline.extractJson(raw)).isEqualTo(raw);
    }

    @Test
    void extractJsonStripsCodeFence() {
        String raw = "```json\n[{\"serviceName\":\"owner-service\"}]\n```";
        assertThat(SinglePromptBaseline.extractJson(raw))
                .isEqualTo("[{\"serviceName\":\"owner-service\"}]");
    }

    @Test
    void extractJsonHandlesProseBeforeArray() {
        String raw = "Here is the decomposition:\n[{\"serviceName\":\"vet-service\"}]";
        assertThat(SinglePromptBaseline.extractJson(raw))
                .isEqualTo("[{\"serviceName\":\"vet-service\"}]");
    }

    @Test
    void extractJsonReturnsEmptyArrayOnGarbage() {
        assertThat(SinglePromptBaseline.extractJson("This is not JSON at all."))
                .isEqualTo("[]");
    }

    // ─── SinglePromptBaseline.countServices ──────────────────────────────────

    @Test
    void countServicesOnValidJson() {
        String raw = "[{\"serviceName\":\"a\"},{\"serviceName\":\"b\"}]";
        assertThat(SinglePromptBaseline.countServices(raw)).isEqualTo(2);
    }

    @Test
    void countServicesOnEmptyArray() {
        assertThat(SinglePromptBaseline.countServices("[]")).isEqualTo(0);
    }

    @Test
    void countServicesOnNoArrayFallsBackToZero() {
        // extractJson returns "[]" when no brackets found → Jackson parses empty array → 0
        assertThat(SinglePromptBaseline.countServices("not json at all")).isEqualTo(0);
    }

    @Test
    void countServicesOnMalformedArrayReturnsMinus1() {
        // Both [ and ] found, but content is structurally broken → Jackson throws → -1
        assertThat(SinglePromptBaseline.countServices("[{broken content}]")).isEqualTo(-1);
    }

    // ─── SourceCollector ─────────────────────────────────────────────────────

    @Test
    void collectsJavaFilesWithPathHeaders(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("Foo.java"), "class Foo {}");
        Files.writeString(tmp.resolve("Bar.java"), "class Bar {}");

        SourceCollector.CollectedSources result = new SourceCollector(100_000).collect(tmp);

        assertThat(result.filesIncluded()).isEqualTo(2);
        assertThat(result.filesSkipped()).isEqualTo(0);
        assertThat(result.concatenated()).contains("// ===== Bar.java =====");
        assertThat(result.concatenated()).contains("// ===== Foo.java =====");
    }

    @Test
    void respectsCharBudget(@TempDir Path tmp) throws IOException {
        // Each file is ~20 chars of content; header adds ~30 more.
        // Budget of 60 chars should fit exactly one file.
        Files.writeString(tmp.resolve("A.java"), "class A { /* padding */ }");
        Files.writeString(tmp.resolve("B.java"), "class B { /* padding */ }");

        SourceCollector.CollectedSources result = new SourceCollector(60).collect(tmp);

        assertThat(result.filesIncluded()).isLessThan(2);
        assertThat(result.filesSkipped()).isGreaterThan(0);
    }

    @Test
    void emptyDirReturnsZeroFiles(@TempDir Path tmp) {
        SourceCollector.CollectedSources result = new SourceCollector(100_000).collect(tmp);
        assertThat(result.filesIncluded()).isEqualTo(0);
        assertThat(result.concatenated()).isEmpty();
    }

    @Test
    void nonExistentDirReturnsZeroFiles() {
        Path missing = Path.of("/tmp/lcm-does-not-exist-" + System.nanoTime());
        SourceCollector.CollectedSources result = new SourceCollector(100_000).collect(missing);
        assertThat(result.filesIncluded()).isEqualTo(0);
    }

    @Test
    void includedPathsAreRelative(@TempDir Path tmp) throws IOException {
        Path sub = tmp.resolve("com/example");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("Service.java"), "class Service {}");

        SourceCollector.CollectedSources result = new SourceCollector(100_000).collect(tmp);

        assertThat(result.includedPaths()).hasSize(1);
        assertThat(result.includedPaths().get(0)).doesNotStartWith("/");
        assertThat(result.includedPaths().get(0)).contains("Service.java");
    }

    // ─── Baseline with stubbed model ─────────────────────────────────────────

    @Test
    void baselineRunSavesFilesToResultsDir(@TempDir Path srcDir,
                                           @TempDir Path resultsDir) throws IOException {
        // Arrange: a tiny Java source tree
        Files.writeString(srcDir.resolve("Owner.java"), "class Owner {}");

        // Stub LLM
        ChatLanguageModel stubModel = mock(ChatLanguageModel.class);
        String fakeJson = "[{\"serviceName\":\"owner-service\",\"classFqns\":[\"Owner\"],"
                + "\"description\":\"Owns owners\",\"rationale\":\"Cohesive\"}]";
        when(stubModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from(fakeJson), new TokenUsage(100, 500)));

        // Concrete baseline using anonymous subclass
        SinglePromptBaseline baseline = new SinglePromptBaseline() {
            @Override protected ChatLanguageModel model() { return stubModel; }
            @Override protected String systemId() { return "single-prompt-test"; }
            @Override protected String modelId()  { return "test-model"; }
            @Override protected int contextWindowTokens() { return 10_000; }
        };

        BenchmarkSpec spec = new BenchmarkSpec("test-repo", srcDir, 100);

        // Act
        BaselineResult result = baseline.run(
                spec,
                srcDir.getParent(),  // projectRoot resolves srcDir.srcDirectory relative to itself
                resultsDir);

        // Assert result fields
        assertThat(result.success()).isTrue();
        assertThat(result.repoName()).isEqualTo("test-repo");
        assertThat(result.servicesExtracted()).isEqualTo(1);
        assertThat(result.filesIncluded()).isEqualTo(1);
        assertThat(result.rawResponse()).isEqualTo(fakeJson);
    }

    @Test
    void baselineRunWritesThreeOutputFiles(@TempDir Path srcDir,
                                           @TempDir Path resultsDir) throws IOException {
        Files.writeString(srcDir.resolve("Vet.java"), "class Vet {}");

        ChatLanguageModel stubModel = mock(ChatLanguageModel.class);
        when(stubModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("[]"), new TokenUsage(50, 100)));

        SinglePromptBaseline baseline = new SinglePromptBaseline() {
            @Override protected ChatLanguageModel model() { return stubModel; }
            @Override protected String systemId() { return "single-prompt-test"; }
            @Override protected String modelId()  { return "test"; }
            @Override protected int contextWindowTokens() { return 10_000; }
        };

        baseline.run(new BenchmarkSpec("vet-repo", srcDir, 50), srcDir.getParent(), resultsDir);

        Path outDir = resultsDir.resolve("vet-repo").resolve("single-prompt-test");
        assertThat(outDir.resolve("response_raw.txt")).exists();
        assertThat(outDir.resolve("response.json")).exists();
        assertThat(outDir.resolve("metadata.json")).exists();
    }

    @Test
    void baselineRunTokenCountInMetadata(@TempDir Path srcDir,
                                         @TempDir Path resultsDir) throws IOException {
        Files.writeString(srcDir.resolve("X.java"), "class X {}");

        ChatLanguageModel stubModel = mock(ChatLanguageModel.class);
        when(stubModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("[]"), new TokenUsage(200, 1000)));

        SinglePromptBaseline baseline = new SinglePromptBaseline() {
            @Override protected ChatLanguageModel model() { return stubModel; }
            @Override protected String systemId() { return "single-prompt-test"; }
            @Override protected String modelId()  { return "test"; }
            @Override protected int contextWindowTokens() { return 10_000; }
        };

        BaselineResult r = baseline.run(
                new BenchmarkSpec("tok-repo", srcDir, 10), srcDir.getParent(), resultsDir);

        assertThat(r.tokensUsed()).isEqualTo(1200);

        String meta = Files.readString(resultsDir.resolve("tok-repo")
                .resolve("single-prompt-test").resolve("metadata.json"));
        assertThat(meta).contains("\"tokensUsed\" : 1200");
    }
}
