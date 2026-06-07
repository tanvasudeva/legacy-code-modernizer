package com.legacy.modernizer.controller;

import com.legacy.modernizer.agent.DocGenAgent;
import com.legacy.modernizer.agent.DocGenerationResult;
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
 * POST /api/jobs/{id}/generate-docs/{boundaryId}
 *
 * <p>Generates all three documentation artefacts for a service boundary:
 * <ol>
 *   <li>Loads existing {@code SERVICE_CODE} artifacts for source context.</li>
 *   <li>Fetches RAG snippets (query: "OpenAPI documentation patterns existing specs").</li>
 *   <li>Calls {@link DocGenAgent} → OpenAPI YAML, ADR, runbook.</li>
 *   <li>Returns file-level summary; each document stored as the appropriate
 *       {@link ArtifactType} ({@code OPENAPI_SPEC}, {@code ADR}, {@code RUNBOOK}).</li>
 * </ol>
 *
 * <p>Prerequisite: {@code POST /api/jobs/{id}/generate-service/{boundaryId}} must have run.
 */
@RestController
@RequestMapping("/api/jobs")
public class DocGenController {

    private static final Logger log = LoggerFactory.getLogger(DocGenController.class);
    private static final String RAG_DOC_QUERY =
            "OpenAPI documentation patterns existing specs Swagger annotations";

    private final DocGenAgent               docGenAgent;
    private final ServiceBoundaryRepository boundaryRepository;
    private final ArtifactRepository        artifactRepository;
    private final MigrationJobRepository    jobRepository;
    private final RagRetriever              ragRetriever;

    public DocGenController(DocGenAgent docGenAgent,
                            ServiceBoundaryRepository boundaryRepository,
                            ArtifactRepository artifactRepository,
                            MigrationJobRepository jobRepository,
                            RagRetriever ragRetriever) {
        this.docGenAgent        = docGenAgent;
        this.boundaryRepository = boundaryRepository;
        this.artifactRepository = artifactRepository;
        this.jobRepository      = jobRepository;
        this.ragRetriever       = ragRetriever;
    }

    @PostMapping("/{id}/generate-docs/{boundaryId}")
    public ResponseEntity<?> generateDocs(@PathVariable Long id,
                                          @PathVariable Long boundaryId) {
        if (!jobRepository.existsById(id)) return ResponseEntity.notFound().build();

        ServiceBoundary boundary;
        try {
            boundary = boundaryRepository.findById(boundaryId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "ServiceBoundary not found: " + boundaryId));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }

        String serviceName = boundary.getServiceName();
        List<Artifact> serviceArtifacts = artifactRepository
                .findByJobIdAndClassFqn(id, serviceName)
                .stream()
                .filter(a -> a.getArtifactType() == ArtifactType.SERVICE_CODE)
                .toList();

        if (serviceArtifacts.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No SERVICE_CODE artifacts found. Run /generate-service first."));
        }

        String pkg            = toPackageName(serviceName);
        String entityName     = extractEntityName(serviceArtifacts);
        String controllerSrc  = findContent(serviceArtifacts, "Controller.java");
        String serviceSrc     = findContent(serviceArtifacts, "Service.java");
        String entitySrc      = findContent(serviceArtifacts, "entity/" + entityName + ".java");

        List<String> ragSnippets = fetchRagSnippets(id);

        try {
            log.info("[doc-ctrl] Generating docs for {} (entity={}, job={})",
                    serviceName, entityName, id);
            DocGenerationResult result = docGenAgent.generate(
                    id, boundary, pkg, entityName,
                    controllerSrc, serviceSrc, entitySrc, ragSnippets);

            return ResponseEntity.ok(Map.of(
                    "jobId",           id,
                    "boundaryId",      boundaryId,
                    "serviceName",     serviceName,
                    "docsGenerated",   result.files().size(),
                    "artifactsStored", result.artifacts().size(),
                    "taskId",          result.task().getId(),
                    "ragSnippets",     ragSnippets.size(),
                    "documents",       result.files().stream()
                            .map(f -> Map.of(
                                    "filePath", f.filePath(),
                                    "bytes",    String.valueOf(f.content().length())))
                            .toList()
            ));
        } catch (Exception e) {
            log.error("[doc-ctrl] Failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // -------------------------------------------------------------------------

    static String toPackageName(String svc) {
        return svc.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    static String extractEntityName(List<Artifact> artifacts) {
        return artifacts.stream()
                .map(Artifact::getFilePath)
                .filter(p -> p != null && p.contains("/controller/") && p.endsWith("Controller.java"))
                .map(p -> p.substring(p.lastIndexOf('/') + 1).replace("Controller.java", ""))
                .findFirst()
                .orElse("Entity");
    }

    private static String findContent(List<Artifact> artifacts, String suffix) {
        return artifacts.stream()
                .filter(a -> a.getFilePath() != null && a.getFilePath().endsWith(suffix))
                .map(Artifact::getContent)
                .findFirst()
                .orElse("");
    }

    private List<String> fetchRagSnippets(Long jobId) {
        try {
            return ragRetriever.retrieve(jobId, RAG_DOC_QUERY, 3)
                    .stream().map(CodeChunk::body)
                    .filter(b -> b != null && !b.isBlank()).toList();
        } catch (Exception e) {
            log.warn("[doc-ctrl] RAG unavailable: {}", e.getMessage());
            return List.of();
        }
    }
}
