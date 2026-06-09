package com.legacy.modernizer.eval.metric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 4.5 — Baseline code extractor.
 *
 * <p>Parses raw LLM response text (from {@code response_raw.txt}) to extract
 * compilable Java code blocks, infers correct package/class structure, writes
 * the files to a temp directory, generates a minimal pom.xml, and attempts
 * {@code mvn compile} to produce a real compilation rate rather than hardcoded 0.0.
 *
 * <p>Extraction: every <code>```java … ```</code> fenced block is captured
 * using {@link Pattern#DOTALL}.  The package declaration and first public
 * type name in each block are used to derive the nested source path.
 *
 * <p>A "test class" is any block whose content contains {@code @Test} or
 * imports {@code org.junit} — used by {@link CoverageMetric} to decide
 * whether to attempt {@code mvn test}.
 */
@Component
public class BaselineCodeExtractor {

    private static final Logger log = LoggerFactory.getLogger(BaselineCodeExtractor.class);

    private static final Pattern JAVA_BLOCK = Pattern.compile(
            "```java\\s*\\n(.*?)```", Pattern.DOTALL);

    private static final Pattern PACKAGE_STMT = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final Pattern TYPE_NAME = Pattern.compile(
            "(?:public\\s+)?(?:class|interface|enum|record)\\s+(\\w+)");

    private static final int COMPILE_TIMEOUT_SECS = 120;

    // ─── Public result types ──────────────────────────────────────────────────

    /**
     * Raw extraction result before any compilation attempt.
     *
     * @param blocks        individual Java source strings extracted from the response
     * @param hasTestClasses true if any block contains {@code @Test} or JUnit imports
     */
    public record ExtractedCode(
            List<String> blocks,
            boolean      hasTestClasses,
            int          totalBlocks
    ) {}

    /**
     * Result of attempting to compile the extracted code.
     *
     * @param success            true if {@code mvn compile} exited 0
     * @param totalClasses       number of Java blocks extracted
     * @param errorCount         number of {@code error:} / {@code [ERROR]} lines in output
     * @param compilationRate    {@code successfulClasses / totalClasses} (0.0–1.0)
     * @param metadata           detail map for persistence
     */
    public record BaselineCompilationResult(
            boolean             success,
            int                 totalClasses,
            int                 errorCount,
            double              compilationRate,
            Map<String, Object> metadata
    ) {}

    // ─── Extraction ───────────────────────────────────────────────────────────

    /**
     * Extracts all {@code ```java} blocks from the raw LLM response string.
     * Returns an empty result if the text is blank or null.
     */
    public ExtractedCode extract(String rawResponseText) {
        if (rawResponseText == null || rawResponseText.isBlank()) {
            return new ExtractedCode(List.of(), false, 0);
        }
        List<String> blocks = new ArrayList<>();
        Matcher m = JAVA_BLOCK.matcher(rawResponseText);
        while (m.find()) {
            String block = m.group(1).strip();
            if (!block.isEmpty()) blocks.add(block);
        }
        boolean hasTests = blocks.stream().anyMatch(b ->
                b.contains("@Test") || b.contains("import org.junit"));
        log.debug("[extractor] {} java blocks found, hasTests={}", blocks.size(), hasTests);
        return new ExtractedCode(blocks, hasTests, blocks.size());
    }

    // ─── Extraction + compilation ─────────────────────────────────────────────

    /**
     * Extracts Java blocks, writes them to a temp directory with correct
     * package/class structure, generates a minimal pom.xml, and runs
     * {@code mvn compile -q --no-transfer-progress} with a
     * {@value #COMPILE_TIMEOUT_SECS}s timeout.
     *
     * <p>The temp directory is deleted on return regardless of outcome.
     *
     * @param rawResponseText full text of {@code response_raw.txt}
     * @param systemId        e.g. {@code "single-prompt-gpt4o"} for logging
     */
    public BaselineCompilationResult extractAndCompile(String rawResponseText, String systemId) {
        ExtractedCode extracted = extract(rawResponseText);
        if (extracted.totalBlocks() == 0) {
            log.info("[extractor][{}] No java blocks found — compilationRate=0.0", systemId);
            return new BaselineCompilationResult(false, 0, 0, 0.0,
                    Map.of("reason", "no ```java blocks in LLM response", "system", systemId));
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("lcm-baseline-" + systemId + "-");

            // Write pom.xml
            Files.writeString(tempDir.resolve("pom.xml"), generatePom());

            // Write each block to the inferred path
            int written = 0;
            Map<String, String> fileMap = new LinkedHashMap<>();
            for (int i = 0; i < extracted.blocks().size(); i++) {
                String src  = extracted.blocks().get(i);
                String path = inferFilePath(src, i);
                Path   target = tempDir.resolve(path).normalize();
                if (!target.startsWith(tempDir)) {
                    log.warn("[extractor] Path traversal prevented for block {}", i);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, src);
                fileMap.put(path, "written");
                written++;
            }
            log.info("[extractor][{}] Wrote {}/{} files, running mvn compile …",
                    systemId, written, extracted.totalBlocks());

            if (written == 0) {
                return new BaselineCompilationResult(false, extracted.totalBlocks(), 0, 0.0,
                        Map.of("reason", "all paths rejected (traversal guard)", "system", systemId));
            }

            // Run mvn compile
            CompileOutput out = runCompile(tempDir);
            int errors = out.errorCount();

            // Estimate per-class success: if exit 0 → all compiled; else use error count
            int totalClasses   = written;
            int failedClasses  = (out.exitCode() == 0) ? 0 : Math.min(errors, totalClasses);
            int successClasses = totalClasses - failedClasses;
            double rate        = totalClasses == 0 ? 0.0 : (double) successClasses / totalClasses;

            log.info("[extractor][{}] exit={} errors={} rate={}", systemId, out.exitCode(), errors, rate);
            return new BaselineCompilationResult(
                    out.exitCode() == 0, totalClasses, errors, rate,
                    Map.of(
                            "totalBlocks",    extracted.totalBlocks(),
                            "writtenFiles",   written,
                            "exitCode",       out.exitCode(),
                            "errorCount",     errors,
                            "files",          fileMap.keySet().stream().toList(),
                            "system",         systemId
                    ));

        } catch (Exception e) {
            log.warn("[extractor][{}] Failed: {}", systemId, e.getMessage());
            return new BaselineCompilationResult(false, extracted.totalBlocks(), 0, 0.0,
                    Map.of("error", e.getMessage(), "system", systemId));
        } finally {
            deleteTree(tempDir);
        }
    }

    // ─── Path inference ───────────────────────────────────────────────────────

    /**
     * Infers a source path from a Java code block.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Extract package from {@code package com.example.foo;} statement.</li>
     *   <li>Extract first public type name (class/interface/enum/record).</li>
     *   <li>Combine: {@code src/main/java/com/example/foo/TypeName.java}.</li>
     *   <li>Fallback: {@code src/main/java/extracted/Block{index}.java}.</li>
     * </ol>
     *
     * <p>If the block contains {@code @Test} or JUnit imports, the path uses
     * {@code src/test/java/…} instead.
     */
    public String inferFilePath(String javaSource, int blockIndex) {
        boolean isTest = javaSource.contains("@Test") || javaSource.contains("import org.junit");
        String srcRoot = isTest ? "src/test/java" : "src/main/java";

        String pkg   = extractPackage(javaSource);
        String clazz = extractTypeName(javaSource);

        if (pkg.isEmpty() && clazz.isEmpty()) {
            return srcRoot + "/extracted/Block" + blockIndex + ".java";
        }

        String pkgPath = pkg.isEmpty() ? "extracted" : pkg.replace('.', '/');
        String fname   = clazz.isEmpty() ? "Block" + blockIndex : clazz;
        return srcRoot + "/" + pkgPath + "/" + fname + ".java";
    }

    public static String extractPackage(String src) {
        Matcher m = PACKAGE_STMT.matcher(src);
        return m.find() ? m.group(1).strip() : "";
    }

    public static String extractTypeName(String src) {
        Matcher m = TYPE_NAME.matcher(src);
        return m.find() ? m.group(1).strip() : "";
    }

    // ─── pom.xml generation ───────────────────────────────────────────────────

    /**
     * Generates a minimal Spring Boot 3.2.5 pom.xml sufficient to compile
     * typical LLM-generated service code ({@code jakarta.*}, JPA, Lombok, Web).
     */
    public static String generatePom() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.2.5</version>
                        <relativePath/>
                    </parent>

                    <groupId>com.baseline.eval</groupId>
                    <artifactId>baseline-eval</artifactId>
                    <version>0.0.1-SNAPSHOT</version>

                    <properties>
                        <java.version>21</java.version>
                        <maven.compiler.source>21</maven.compiler.source>
                        <maven.compiler.target>21</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-data-jpa</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                            <scope>runtime</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <optional>true</optional>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """;
    }

    // ─── Maven execution ──────────────────────────────────────────────────────

    record CompileOutput(int exitCode, int errorCount, String rawOutput) {}

    CompileOutput runCompile(Path projectDir) throws IOException, InterruptedException {
        String mvn = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "mvn.cmd" : "mvn";

        Process proc = new ProcessBuilder(mvn, "compile", "-q", "--no-transfer-progress")
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .start();

        boolean finished = proc.waitFor(COMPILE_TIMEOUT_SECS, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            log.warn("[extractor] mvn compile timed out after {}s", COMPILE_TIMEOUT_SECS);
            return new CompileOutput(-1, 0, "TIMEOUT");
        }

        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode  = proc.exitValue();
        int errors    = countErrors(output);
        return new CompileOutput(exitCode, errors, output);
    }

    /** Counts distinct compilation error lines in Maven output. */
    public static int countErrors(String output) {
        if (output == null) return 0;
        return (int) output.lines()
                .filter(l -> l.contains("error:") || l.startsWith("[ERROR]"))
                .count();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        } catch (IOException ignored) {}
    }
}
