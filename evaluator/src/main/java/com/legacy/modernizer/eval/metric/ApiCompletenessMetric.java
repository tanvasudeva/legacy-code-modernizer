package com.legacy.modernizer.eval.metric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Phase 4.3 — API completeness metric.
 *
 * <p>Measures what fraction of the original monolith's <em>public method names</em>
 * are preserved in the generated microservices.
 *
 * <p><b>Multi-agent:</b> uses JavaParser to extract public method names from the
 * original benchmark source and from the SERVICE_CODE artifact strings.
 * Score = |generated ∩ original| / |original|.
 *
 * <p><b>Baselines:</b> the response JSON lists service boundaries with
 * {@code classFqns}.  We check what fraction of the original source's class
 * simple names appear in those classFqns.
 * Score = |baseline_classNames ∩ original_classNames| / |original_classNames|.
 */
@Component
public class ApiCompletenessMetric {

    private static final Logger log = LoggerFactory.getLogger(ApiCompletenessMetric.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    public record Result(
            double              score,
            int                 originalCount,
            int                 preservedCount,
            Map<String, Object> metadata
    ) {}

    // ─── Multi-agent ──────────────────────────────────────────────────────────

    /**
     * Compares public method names between the original source and generated artifacts.
     *
     * @param originalSrcDir  root of the benchmark repo src directory
     * @param artifacts        all SERVICE_CODE artifacts for the job
     */
    public Result evaluate(Path originalSrcDir, List<Artifact> artifacts) {
        Set<String> originalMethods  = extractPublicMethodNames(originalSrcDir);
        Set<String> generatedMethods = extractMethodNamesFromArtifacts(artifacts);

        log.info("[api-completeness] original={}, generated={}",
                originalMethods.size(), generatedMethods.size());

        if (originalMethods.isEmpty()) {
            return new Result(0.0, 0, 0, Map.of("reason", "no original source found at " + originalSrcDir));
        }

        Set<String> intersection = new HashSet<>(generatedMethods);
        intersection.retainAll(originalMethods);

        double score = (double) intersection.size() / originalMethods.size();
        return new Result(score, originalMethods.size(), intersection.size(), Map.of(
                "originalMethodCount",  originalMethods.size(),
                "generatedMethodCount", generatedMethods.size(),
                "preservedCount",       intersection.size()
        ));
    }

    // ─── Baseline ─────────────────────────────────────────────────────────────

    /**
     * Checks what fraction of original class names are claimed by the baseline's
     * service boundary plan.
     *
     * @param originalSrcDir   root of the benchmark repo src directory
     * @param responseJsonPath path to {@code response.json} from the baseline run
     * @param systemId         e.g. {@code "single-prompt-gpt4o"}
     */
    public Result evaluateBaseline(Path originalSrcDir, Path responseJsonPath, String systemId) {
        Set<String> originalClassNames = extractClassSimpleNames(originalSrcDir);
        Set<String> claimedFqns        = parseClaimedFqns(responseJsonPath);
        Set<String> claimedSimple      = toSimpleNames(claimedFqns);

        log.info("[api-completeness][{}] original classes={}, claimed={}",
                systemId, originalClassNames.size(), claimedSimple.size());

        if (originalClassNames.isEmpty()) {
            return new Result(0.0, 0, 0, Map.of("reason", "no original source found"));
        }

        Set<String> intersection = new HashSet<>(claimedSimple);
        intersection.retainAll(originalClassNames);

        double score = (double) intersection.size() / originalClassNames.size();
        return new Result(score, originalClassNames.size(), intersection.size(), Map.of(
                "originalClassCount", originalClassNames.size(),
                "claimedClassCount",  claimedSimple.size(),
                "matchedClassCount",  intersection.size()
        ));
    }

    // ─── Source extraction ────────────────────────────────────────────────────

    /** Extracts all public method names from .java files under srcRoot. */
    public Set<String> extractPublicMethodNames(Path srcRoot) {
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(srcRoot)) return names;
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .forEach(file -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(file);
                        new PublicMethodVisitor().visit(cu, names);
                    } catch (ParseProblemException | IOException e) {
                        log.debug("[api-completeness] Skip {}: {}", file.getFileName(), e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.warn("[api-completeness] Walk failed: {}", e.getMessage());
        }
        return names;
    }

    /** Extracts public method names from SERVICE_CODE artifact content strings. */
    public Set<String> extractMethodNamesFromArtifacts(List<Artifact> artifacts) {
        Set<String> names = new HashSet<>();
        for (Artifact a : artifacts) {
            if (a.getArtifactType() != ArtifactType.SERVICE_CODE) continue;
            if (a.getContent() == null || a.getContent().isBlank()) continue;
            try {
                CompilationUnit cu = StaticJavaParser.parse(a.getContent());
                new PublicMethodVisitor().visit(cu, names);
            } catch (ParseProblemException e) {
                log.debug("[api-completeness] Parse error in {}: {}", a.getFilePath(), e.getMessage());
            }
        }
        return names;
    }

    /** Extracts class simple names from .java files under srcRoot. */
    Set<String> extractClassSimpleNames(Path srcRoot) {
        Set<String> names = new HashSet<>();
        if (!Files.isDirectory(srcRoot)) return names;
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    String fname = p.getFileName().toString();
                    names.add(fname.replace(".java", ""));
                });
        } catch (IOException e) {
            log.warn("[api-completeness] Walk failed: {}", e.getMessage());
        }
        return names;
    }

    /** Parses the baseline response.json and collects all classFqn values. */
    Set<String> parseClaimedFqns(Path responseJsonPath) {
        Set<String> fqns = new HashSet<>();
        if (responseJsonPath == null || !Files.exists(responseJsonPath)) return fqns;
        try {
            String json = Files.readString(responseJsonPath);
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) return fqns;
            for (JsonNode service : root) {
                JsonNode classFqns = service.get("classFqns");
                if (classFqns != null && classFqns.isArray()) {
                    classFqns.forEach(n -> fqns.add(n.asText()));
                }
            }
        } catch (Exception e) {
            log.warn("[api-completeness] Cannot parse {}: {}", responseJsonPath, e.getMessage());
        }
        return fqns;
    }

    public static Set<String> toSimpleNames(Set<String> fqns) {
        Set<String> simple = new HashSet<>();
        for (String fqn : fqns) {
            int dot = fqn.lastIndexOf('.');
            simple.add(dot >= 0 ? fqn.substring(dot + 1) : fqn);
        }
        return simple;
    }

    // ─── JavaParser visitor ───────────────────────────────────────────────────

    private static class PublicMethodVisitor extends VoidVisitorAdapter<Set<String>> {
        @Override
        public void visit(MethodDeclaration n, Set<String> names) {
            if (n.isPublic()) names.add(n.getNameAsString());
            super.visit(n, names);
        }
    }
}
