package com.legacy.modernizer.eval.metric;

import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4.3 — Coverage metric.
 *
 * <p>For the <b>multi-agent</b> system: writes SERVICE_CODE + TEST_CODE artifacts to
 * a temp directory, injects the JaCoCo plugin into each service pom.xml, runs
 * {@code mvn test}, then parses {@code target/site/jacoco/jacoco.xml} for line
 * coverage.  Score = total_lines_covered / (covered + missed), averaged across services.
 *
 * <p>If Maven test execution fails (e.g. DB unavailable), returns 0.0 with metadata
 * explaining the failure.
 *
 * <p>For <b>baselines</b>: score = 0.0 — no test code is generated.
 */
@Component
public class CoverageMetric {

    private static final Logger log = LoggerFactory.getLogger(CoverageMetric.class);

    // JaCoCo plugin block injected before </plugins> in each service pom
    private static final String JACOCO_PLUGIN = """
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.11</version>
                <executions>
                    <execution><goals><goal>prepare-agent</goal></goals></execution>
                    <execution>
                        <id>report</id><phase>test</phase>
                        <goals><goal>report</goal></goals>
                    </execution>
                </executions>
            </plugin>
            """;

    public record Result(
            double              score,
            Map<String, Object> metadata
    ) {}

    // ─── Multi-agent ──────────────────────────────────────────────────────────

    public Result evaluate(List<Artifact> artifacts) {
        List<Artifact> code  = artifacts.stream()
                .filter(a -> a.getArtifactType() == ArtifactType.SERVICE_CODE
                          || a.getArtifactType() == ArtifactType.TEST_CODE)
                .filter(a -> a.getFilePath() != null && a.getContent() != null)
                .toList();

        if (code.isEmpty()) {
            return new Result(0.0, Map.of("reason", "no SERVICE_CODE or TEST_CODE artifacts"));
        }

        // Group by service
        Map<String, List<Artifact>> byService = new LinkedHashMap<>();
        for (Artifact a : code) {
            String svc = a.getClassFqn() != null ? a.getClassFqn() : "_misc";
            byService.computeIfAbsent(svc, k -> new ArrayList<>()).add(a);
        }

        double totalScore = 0.0;
        int    measured   = 0;
        Map<String, Object> serviceDetails = new LinkedHashMap<>();

        for (Map.Entry<String, List<Artifact>> entry : byService.entrySet()) {
            String svc = entry.getKey();
            double svcScore = runCoverage(svc, entry.getValue());
            serviceDetails.put(svc, svcScore);
            if (svcScore >= 0) { totalScore += svcScore; measured++; }
        }

        double avg = measured == 0 ? 0.0 : totalScore / measured;
        log.info("[coverage] avg={} ({} services measured)", avg, measured);
        return new Result(avg, Map.of("services", serviceDetails, "servicesMeasured", measured));
    }

    // ─── Baseline ─────────────────────────────────────────────────────────────

    public Result evaluateBaseline(String systemId) {
        return new Result(0.0, Map.of(
                "reason", "Single-prompt baseline generates no test code",
                "system", systemId));
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private double runCoverage(String serviceName, List<Artifact> files) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("lcm-cov-" + serviceName + "-");
            for (Artifact a : files) {
                Path target = tempDir.resolve(a.getFilePath()).normalize();
                if (!target.startsWith(tempDir)) continue;
                Files.createDirectories(target.getParent());
                Files.writeString(target, a.getContent());
            }

            Path pomFile = tempDir.resolve("pom.xml");
            if (!Files.exists(pomFile)) {
                log.warn("[coverage] No pom.xml for {} — skipping", serviceName);
                return -1;
            }

            // Inject JaCoCo plugin
            String pom = Files.readString(pomFile);
            if (!pom.contains("jacoco")) {
                pom = pom.replace("</plugins>", JACOCO_PLUGIN + "</plugins>");
                Files.writeString(pomFile, pom);
            }

            String mvn = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "mvn.cmd" : "mvn";

            Process proc = new ProcessBuilder(mvn, "test", "-q", "--no-transfer-progress")
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            proc.getInputStream().readAllBytes(); // drain
            int exit = proc.waitFor();

            if (exit != 0) {
                log.debug("[coverage] mvn test failed for {} (exit {})", serviceName, exit);
                return 0.0;
            }

            // Parse jacoco.xml
            Path xmlReport = tempDir.resolve("target/site/jacoco/jacoco.xml");
            if (!Files.exists(xmlReport)) {
                log.warn("[coverage] No jacoco.xml for {}", serviceName);
                return 0.0;
            }
            return parseLineCoverage(xmlReport);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (Exception e) {
            log.warn("[coverage] Error measuring {}: {}", serviceName, e.getMessage());
            return 0.0;
        } finally {
            deleteTree(tempDir);
        }
    }

    public static double parseLineCoverage(Path xmlPath) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(xmlPath.toFile());

        // <counter type="LINE" missed="X" covered="Y"/> at the report root level
        NodeList counters = doc.getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            var node = counters.item(i);
            if ("LINE".equals(node.getAttributes().getNamedItem("type").getTextContent())) {
                double missed  = Double.parseDouble(
                        node.getAttributes().getNamedItem("missed").getTextContent());
                double covered = Double.parseDouble(
                        node.getAttributes().getNamedItem("covered").getTextContent());
                double total = missed + covered;
                return total == 0 ? 0.0 : covered / total;
            }
        }
        return 0.0;
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder())
             .map(Path::toFile)
             .forEach(java.io.File::delete);
        } catch (IOException ignored) {}
    }
}
