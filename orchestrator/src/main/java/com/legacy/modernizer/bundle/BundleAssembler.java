package com.legacy.modernizer.bundle;

import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.repository.ArtifactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phase 3.4 — Bundle Assembler.
 *
 * <p>Reads all artifacts for a migration job from PostgreSQL and reconstructs
 * a directory tree using each artifact's {@code file_path} column. Wraps the
 * tree — plus a generated root {@code pom.xml} and {@code docker-compose.yml}
 * — into a ZIP stream for download.
 *
 * <p>File-path routing rules:
 * <ul>
 *   <li>{@code SERVICE_CODE} and {@code TEST_CODE}: placed under
 *       {@code <service-name>/<file_path>}</li>
 *   <li>{@code OPENAPI_SPEC}, {@code ADR}, {@code RUNBOOK}: placed under
 *       {@code <service-name>/<file_path>} (file_path already begins with
 *       {@code docs/})</li>
 *   <li>{@code ORIGINAL} / {@code TRANSFORMED}: skipped — internal pipeline
 *       artefacts, not part of the deployable bundle</li>
 * </ul>
 */
@Component
public class BundleAssembler {

    private static final Logger log = LoggerFactory.getLogger(BundleAssembler.class);

    private static final Set<ArtifactType> BUNDLED_TYPES = Set.of(
            ArtifactType.SERVICE_CODE,
            ArtifactType.TEST_CODE,
            ArtifactType.OPENAPI_SPEC,
            ArtifactType.ADR,
            ArtifactType.RUNBOOK
    );

    private final ArtifactRepository artifactRepository;

    public BundleAssembler(ArtifactRepository artifactRepository) {
        this.artifactRepository = artifactRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Assembles a ZIP bundle for the given job and writes it to {@code out}.
     *
     * @param jobId migration job ID
     * @param out   destination stream (caller is responsible for closing it)
     * @return manifest summarising what was packaged
     */
    public BundleManifest assemble(Long jobId, OutputStream out) throws IOException {
        List<Artifact> all = artifactRepository.findByJobId(jobId);
        log.info("[bundle] Assembling job {} — {} raw artifacts", jobId, all.size());

        // Group by service name → (filePath → artifact).
        // Sort by id ascending so higher-id (latest repair attempt) overwrites earlier versions
        // of the same file, preventing ZipException on duplicate entries.
        Map<String, Map<String, Artifact>> byService = new LinkedHashMap<>();
        for (Artifact a : all.stream()
                .sorted(Comparator.comparingLong(Artifact::getId))
                .toList()) {
            if (!BUNDLED_TYPES.contains(a.getArtifactType())) continue;
            if (a.getFilePath() == null || a.getContent() == null) continue;
            String svc = a.getClassFqn() != null ? a.getClassFqn() : "_misc";
            byService.computeIfAbsent(svc, k -> new LinkedHashMap<>())
                     .put(a.getFilePath(), a);
        }

        // Only use classFqns that look like service slugs (e.g. "owner-service"),
        // not fully-qualified class names (e.g. "org.petclinic.Owner").
        // DD2: sort with the -commons module first so Maven builds it before dependents.
        List<String> serviceNames = byService.keySet().stream()
                .filter(BundleAssembler::isServiceSlug)
                .sorted(commonsFirstComparator())
                .toList();

        // DD2: find the commons module name (if any) to inject its dependency into other services
        Optional<String> commonsModule = serviceNames.stream()
                .filter(s -> s.endsWith("-commons"))
                .findFirst();

        int fileCount = 0;
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeEntry(zip, "pom.xml",           generateRootPom(serviceNames, jobId));
            writeEntry(zip, "docker-compose.yml", generateDockerCompose(serviceNames));
            fileCount += 2;

            for (Map.Entry<String, Map<String, Artifact>> entry : byService.entrySet()) {
                String svc = entry.getKey();
                for (Artifact a : entry.getValue().values()) {
                    String content = a.getContent();
                    // DD2: inject commons dependency into per-service pom.xml artifacts
                    if (commonsModule.isPresent()
                            && !svc.equals(commonsModule.get())
                            && "pom.xml".equals(a.getFilePath())
                            && !content.contains(commonsModule.get())) {
                        content = injectCommonsDependency(content, commonsModule.get());
                    }
                    String path = svc + "/" + a.getFilePath();
                    writeEntry(zip, path, content);
                    fileCount++;
                    log.debug("[bundle] + {}", path);
                }
            }
        }

        log.info("[bundle] Done — job {}: {} services, {} files total",
                jobId, serviceNames.size(), fileCount);
        return new BundleManifest(jobId, serviceNames, fileCount, LocalDateTime.now());
    }

    // -------------------------------------------------------------------------
    // Root pom.xml (multi-module Maven wrapper)
    // -------------------------------------------------------------------------

    String generateRootPom(List<String> serviceNames, Long jobId) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
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

                    <groupId>com.modernized</groupId>
                    <artifactId>modernized-monolith</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <name>modernized-monolith</name>
                """);
        sb.append("    <description>Auto-generated by LCM job ").append(jobId)
                .append("</description>\n\n");
        sb.append("""
                    <properties>
                        <java.version>21</java.version>
                        <maven.compiler.source>21</maven.compiler.source>
                        <maven.compiler.target>21</maven.compiler.target>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                """);

        if (!serviceNames.isEmpty()) {
            sb.append("\n    <modules>\n");
            for (String svc : serviceNames) {
                sb.append("        <module>").append(svc).append("</module>\n");
            }
            sb.append("    </modules>\n");
        }

        sb.append("</project>\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // docker-compose.yml
    // -------------------------------------------------------------------------

    String generateDockerCompose(List<String> serviceNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("version: '3.8'\n\nservices:\n");

        int port = 8081;
        for (String svc : serviceNames) {
            sb.append("  ").append(svc).append(":\n");
            sb.append("    build: ./").append(svc).append("\n");
            sb.append("    ports:\n");
            sb.append("      - \"").append(port).append(":8080\"\n");
            sb.append("    environment:\n");
            sb.append("      - SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/modernized\n");
            sb.append("      - SPRING_DATASOURCE_USERNAME=postgres\n");
            sb.append("      - SPRING_DATASOURCE_PASSWORD=postgres\n");
            sb.append("    depends_on:\n");
            sb.append("      - db\n\n");
            port++;
        }

        sb.append("  db:\n")
          .append("    image: postgres:16-alpine\n")
          .append("    environment:\n")
          .append("      POSTGRES_DB: modernized\n")
          .append("      POSTGRES_USER: postgres\n")
          .append("      POSTGRES_PASSWORD: postgres\n")
          .append("    ports:\n")
          .append("      - \"5432:5432\"\n")
          .append("    volumes:\n")
          .append("      - pgdata:/var/lib/postgresql/data\n\n")
          .append("volumes:\n  pgdata:\n");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Service slugs look like "owner-service", not "org.petclinic.Owner". */
    static boolean isServiceSlug(String name) {
        return name != null && !name.isBlank() && !name.contains(".");
    }

    /**
     * Sorts service names so that any {@code *-commons} module comes first.
     * Maven requires shared libraries to be listed before their dependents.
     */
    static Comparator<String> commonsFirstComparator() {
        return (a, b) -> {
            boolean aCommons = a.endsWith("-commons");
            boolean bCommons = b.endsWith("-commons");
            if (aCommons == bCommons) return a.compareTo(b);
            return aCommons ? -1 : 1;
        };
    }

    /**
     * Injects a {@code <dependency>} on the commons module into a Maven pom.xml string.
     *
     * <p>Inserts before the closing {@code </dependencies>} tag. If no
     * {@code <dependencies>} block exists, appends one before {@code </project>}.
     */
    static String injectCommonsDependency(String pomXml, String commonsArtifactId) {
        String dep = String.format("""
                        <dependency>
                            <groupId>com.modernized</groupId>
                            <artifactId>%s</artifactId>
                            <version>1.0.0-SNAPSHOT</version>
                        </dependency>""", commonsArtifactId);

        if (pomXml.contains("</dependencies>")) {
            return pomXml.replace("</dependencies>", dep + "\n    </dependencies>");
        }
        // No <dependencies> block — insert one before </project>
        String block = "\n    <dependencies>\n" + dep + "\n    </dependencies>\n";
        return pomXml.replace("</project>", block + "</project>");
    }

    private static void writeEntry(ZipOutputStream zip, String path, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
