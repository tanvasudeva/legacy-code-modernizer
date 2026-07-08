package com.legacy.modernizer.eval.metric;

import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 4.3/4.5 — Compilation metric.
 *
 * <p>For the <b>multi-agent</b> system: groups SERVICE_CODE artifacts by service name,
 * writes each service to a temp directory, then runs {@code mvn compile -q} for
 * each service. Score = services_compiled_ok / total_services (0.0–1.0).
 *
 * <p>For <b>baselines</b>: uses {@link BaselineCodeExtractor} to extract
 * {@code ```java} blocks from the raw LLM response, writes them to a temp directory
 * with a generated pom.xml, and runs {@code mvn compile}.
 * Score = compilable_classes / total_extracted_classes.
 * Returns 0.0 only when the response contained no Java code blocks at all.
 */
@Component
public class CompilationMetric {

    private static final Logger log = LoggerFactory.getLogger(CompilationMetric.class);

    private final BaselineCodeExtractor baselineCodeExtractor;

    public CompilationMetric(BaselineCodeExtractor baselineCodeExtractor) {
        this.baselineCodeExtractor = baselineCodeExtractor;
    }

    public record Result(
            double          score,
            int             servicesOk,
            int             servicesTotal,
            Map<String, Object> metadata
    ) {}

    // ─── Multi-agent evaluation ───────────────────────────────────────────────

    /**
     * Evaluates compilation for a multi-agent job.
     *
     * @param artifacts  all SERVICE_CODE artifacts for the job
     */
    public Result evaluate(List<Artifact> artifacts) {
        List<Artifact> serviceArtifacts = artifacts.stream()
                .filter(a -> a.getArtifactType() == ArtifactType.SERVICE_CODE)
                .filter(a -> a.getFilePath() != null && a.getContent() != null)
                .toList();

        if (serviceArtifacts.isEmpty()) {
            log.warn("[compilation] No SERVICE_CODE artifacts found");
            return new Result(0.0, 0, 0, Map.of("reason", "no SERVICE_CODE artifacts"));
        }

        // Group by classFqn (= service name, e.g. "owner-service")
        Map<String, List<Artifact>> byService = new LinkedHashMap<>();
        for (Artifact a : serviceArtifacts) {
            String svc = a.getClassFqn() != null ? a.getClassFqn() : "_misc";
            byService.computeIfAbsent(svc, k -> new ArrayList<>()).add(a);
        }

        // Install commons-style services first so dependent services can resolve them
        List<String> commonsFirst = byService.keySet().stream()
                .filter(s -> s.endsWith("-commons")).toList();
        List<String> rest = byService.keySet().stream()
                .filter(s -> !s.endsWith("-commons")).toList();

        int ok    = 0;
        int total = byService.size();
        Map<String, Object> serviceDetails = new LinkedHashMap<>();

        for (String svc : commonsFirst) {
            ServiceCompileResult r = installService(svc, byService.get(svc));
            serviceDetails.put(svc, Map.of("success", r.success(), "exitCode", r.exitCode()));
            if (r.success()) ok++;
            log.info("[compilation] {} (commons/install) → {}", svc, r.success() ? "OK" : "FAIL (exit " + r.exitCode() + ")");
        }

        for (String svc : rest) {
            ServiceCompileResult r = compileService(svc, byService.get(svc));
            serviceDetails.put(svc, Map.of("success", r.success(), "exitCode", r.exitCode()));
            if (r.success()) ok++;
            log.info("[compilation] {} → {}", svc, r.success() ? "OK" : "FAIL (exit " + r.exitCode() + ")");
        }

        double score = total == 0 ? 0.0 : (double) ok / total;
        Map<String, Object> meta = new HashMap<>();
        meta.put("servicesOk",    ok);
        meta.put("servicesTotal", total);
        meta.put("services",      serviceDetails);

        log.info("[compilation] score={} ({}/{} services compiled)", score, ok, total);
        return new Result(score, ok, total, meta);
    }

    // ─── Baseline evaluation ──────────────────────────────────────────────────

    /**
     * Extracts {@code ```java} blocks from the raw LLM response and attempts
     * to compile them, returning a real compilation rate instead of hardcoded 0.0.
     *
     * @param systemId        e.g. {@code "single-prompt-gpt4o"}
     * @param rawResponseText full content of {@code response_raw.txt}
     */
    public Result evaluateBaseline(String systemId, String rawResponseText) {
        BaselineCodeExtractor.BaselineCompilationResult r =
                baselineCodeExtractor.extractAndCompile(rawResponseText, systemId);

        int ok    = r.success() ? r.totalClasses() : Math.max(0, r.totalClasses() - r.errorCount());
        int total = r.totalClasses();

        log.info("[compilation][{}] baseline rate={} ({}/{} classes)",
                systemId, r.compilationRate(), ok, total);
        return new Result(r.compilationRate(), ok, total, r.metadata());
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private record ServiceCompileResult(boolean success, int exitCode) {}

    /** Runs {@code mvn install -DskipTests} so the JAR lands in the local repo for dependents. */
    private ServiceCompileResult installService(String serviceName, List<Artifact> files) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("lcm-install-" + serviceName + "-");
            for (Artifact a : files) {
                Path target = tempDir.resolve(a.getFilePath()).normalize();
                if (!target.startsWith(tempDir))
                    throw new SecurityException("Path traversal: " + a.getFilePath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, a.getContent());
            }
            if (!Files.exists(tempDir.resolve("pom.xml"))) {
                log.warn("[compilation] No pom.xml for commons service {} — skipping install", serviceName);
                return new ServiceCompileResult(false, -1);
            }
            String mvn = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "mvn.cmd" : "mvn";
            ProcessBuilder pb = new ProcessBuilder(
                    mvn, "install", "-DskipTests", "-q", "--no-transfer-progress")
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true);
            Process proc   = pb.start();
            String  output = new String(proc.getInputStream().readAllBytes());
            int     exit   = proc.waitFor();
            if (exit != 0) log.debug("[compilation] install {} failed:\n{}", serviceName, output);
            return new ServiceCompileResult(exit == 0, exit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ServiceCompileResult(false, -99);
        } catch (Exception e) {
            log.warn("[compilation] Error installing {}: {}", serviceName, e.getMessage());
            return new ServiceCompileResult(false, -1);
        } finally {
            deleteTree(tempDir);
        }
    }

    private ServiceCompileResult compileService(String serviceName, List<Artifact> files) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("lcm-compile-" + serviceName + "-");
            // Write all files
            for (Artifact a : files) {
                Path target = tempDir.resolve(a.getFilePath()).normalize();
                if (!target.startsWith(tempDir))
                    throw new SecurityException("Path traversal: " + a.getFilePath());
                Files.createDirectories(target.getParent());
                Files.writeString(target, a.getContent());
            }

            // Check pom.xml exists (required for mvn)
            if (!Files.exists(tempDir.resolve("pom.xml"))) {
                log.warn("[compilation] No pom.xml for service {} — skipping", serviceName);
                return new ServiceCompileResult(false, -1);
            }

            String mvn = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "mvn.cmd" : "mvn";

            ProcessBuilder pb = new ProcessBuilder(
                    mvn, "compile", "-q", "--no-transfer-progress")
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true);

            Process proc   = pb.start();
            String  output = new String(proc.getInputStream().readAllBytes());
            int     exit   = proc.waitFor();

            if (exit != 0) {
                log.debug("[compilation] {} failed:\n{}", serviceName, output);
            }
            return new ServiceCompileResult(exit == 0, exit);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ServiceCompileResult(false, -99);
        } catch (Exception e) {
            log.warn("[compilation] Error compiling {}: {}", serviceName, e.getMessage());
            return new ServiceCompileResult(false, -1);
        } finally {
            deleteTree(tempDir);
        }
    }

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder())
             .map(Path::toFile)
             .forEach(java.io.File::delete);
        } catch (IOException e) {
            log.warn("[compilation] Could not delete temp dir {}: {}", dir, e.getMessage());
        }
    }
}
