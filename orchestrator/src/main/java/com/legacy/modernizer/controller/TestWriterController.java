package com.legacy.modernizer.controller;

import com.legacy.modernizer.agent.GeneratedFile;
import com.legacy.modernizer.agent.TestGenerationResult;
import com.legacy.modernizer.agent.TestWriterAgent;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.rag.CodeChunk;
import com.legacy.modernizer.rag.RagRetriever;
import com.legacy.modernizer.repository.ArtifactRepository;
import com.legacy.modernizer.repository.MigrationJobRepository;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * POST /api/jobs/{id}/write-tests/{boundaryId}
 *
 * <p>Generates JUnit 5 tests for a previously generated Spring Boot microservice:
 * <ol>
 *   <li>Loads the {@link ServiceBoundary} and its {@code SERVICE_CODE} artifacts from DB.</li>
 *   <li>Fetches test-pattern RAG snippets (query: "existing test utilities @DataJpaTest patterns").</li>
 *   <li>Calls {@link TestWriterAgent} → 3 files: updated pom, service test, controller test.</li>
 *   <li>Returns artifact summary. Each file is stored as {@code TEST_CODE}.</li>
 * </ol>
 *
 * <p>Prerequisite: {@code POST /api/jobs/{id}/generate-service/{boundaryId}} must have
 * run first so {@code SERVICE_CODE} artifacts exist for this boundary.
 */
@RestController
@RequestMapping("/api/jobs")
public class TestWriterController {

    private static final Logger log = LoggerFactory.getLogger(TestWriterController.class);
    private static final String RAG_TEST_QUERY = "existing test utilities @DataJpaTest patterns JUnit";

    private final TestWriterAgent           testWriterAgent;
    private final ServiceBoundaryRepository boundaryRepository;
    private final ArtifactRepository        artifactRepository;
    private final MigrationJobRepository    jobRepository;
    private final RagRetriever              ragRetriever;

    public TestWriterController(TestWriterAgent testWriterAgent,
                                ServiceBoundaryRepository boundaryRepository,
                                ArtifactRepository artifactRepository,
                                MigrationJobRepository jobRepository,
                                RagRetriever ragRetriever) {
        this.testWriterAgent    = testWriterAgent;
        this.boundaryRepository = boundaryRepository;
        this.artifactRepository = artifactRepository;
        this.jobRepository      = jobRepository;
        this.ragRetriever       = ragRetriever;
    }

    @PostMapping("/{id}/write-tests/{boundaryId}")
    public ResponseEntity<?> writeTests(@PathVariable Long id,
                                        @PathVariable Long boundaryId) {
        if (!jobRepository.existsById(id)) return ResponseEntity.notFound().build();

        ServiceBoundary boundary;
        try {
            boundary = boundaryRepository.findById(boundaryId)
                    .orElseThrow(() -> new NoSuchElementException("ServiceBoundary not found: " + boundaryId));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }

        String serviceName = boundary.getServiceName();

        // Load SERVICE_CODE artifacts for this boundary
        List<Artifact> serviceArtifacts = artifactRepository
                .findByJobIdAndClassFqn(id, serviceName)
                .stream()
                .filter(a -> a.getArtifactType() == ArtifactType.SERVICE_CODE)
                .toList();

        if (serviceArtifacts.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No SERVICE_CODE artifacts found for boundary " + boundaryId
                            + ". Run /generate-service first."));
        }

        // Extract source files by path suffix
        String pkg            = toPackageName(serviceName);
        String entityName     = extractEntityName(serviceArtifacts, pkg);
        String serviceSource  = findContent(serviceArtifacts, "Service.java");
        String controllerSource = findContent(serviceArtifacts, "Controller.java");
        String entitySource   = findContent(serviceArtifacts, "entity/" + entityName + ".java");
        String pomSource      = findContent(serviceArtifacts, "pom.xml");

        if (serviceSource.isEmpty() || controllerSource.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "SERVICE_CODE artifacts missing Service.java or Controller.java"));
        }

        // RAG: test-pattern snippets — best-effort
        List<String> ragSnippets = fetchRagSnippets(id);

        try {
            log.info("[test-ctrl] Writing tests for {} (entity={}, job={})",
                    serviceName, entityName, id);
            TestGenerationResult result = testWriterAgent.generate(
                    id, serviceName, pkg, entityName,
                    serviceSource, controllerSource, entitySource, pomSource, ragSnippets);

            return ResponseEntity.ok(Map.of(
                    "jobId",          id,
                    "boundaryId",     boundaryId,
                    "serviceName",    serviceName,
                    "entityName",     entityName,
                    "filesGenerated", result.files().size(),
                    "artifactsStored", result.artifacts().size(),
                    "taskId",         result.task().getId(),
                    "ragSnippets",    ragSnippets.size(),
                    "files",          result.files().stream()
                            .map(f -> Map.of(
                                    "filePath", f.filePath(),
                                    "lines", String.valueOf(f.content().lines().count())))
                            .toList()
            ));
        } catch (Exception e) {
            log.error("[test-ctrl] Failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    static String toPackageName(String serviceName) {
        return serviceName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    /** Finds the primary entity name from the artifact paths. */
    static String extractEntityName(List<Artifact> artifacts, String pkg) {
        return artifacts.stream()
                .map(Artifact::getFilePath)
                .filter(p -> p != null && p.contains("/controller/") && p.endsWith("Controller.java"))
                .map(p -> {
                    String filename = p.substring(p.lastIndexOf('/') + 1);
                    return filename.replace("Controller.java", "");
                })
                .findFirst()
                .orElse("Entity");
    }

    private static String findContent(List<Artifact> artifacts, String pathSuffix) {
        return artifacts.stream()
                .filter(a -> a.getFilePath() != null && a.getFilePath().endsWith(pathSuffix))
                .map(Artifact::getContent)
                .findFirst()
                .orElse("");
    }

    private List<String> fetchRagSnippets(Long jobId) {
        try {
            return ragRetriever.retrieve(jobId, RAG_TEST_QUERY, 5)
                    .stream()
                    .map(CodeChunk::body)
                    .filter(b -> b != null && !b.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("[test-ctrl] RAG unavailable: {}", e.getMessage());
            return List.of();
        }
    }
}
