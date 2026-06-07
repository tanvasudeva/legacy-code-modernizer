package com.legacy.modernizer.controller;

import com.legacy.modernizer.agent.GeneratedFile;
import com.legacy.modernizer.agent.ServiceGenerationResult;
import com.legacy.modernizer.agent.ServiceGeneratorAgent;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.rag.CodeChunk;
import com.legacy.modernizer.rag.RagRetriever;
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
 * POST /api/jobs/{id}/generate-service/{boundaryId}
 *
 * <p>Triggers service-level code generation for a single DDD service boundary:
 * <ol>
 *   <li>Validates job and boundary exist.</li>
 *   <li>Fetches top-5 relevant method snippets from Qdrant (RAG).</li>
 *   <li>Calls {@link ServiceGeneratorAgent} → LLM → 7 files.</li>
 *   <li>Returns a summary of all persisted artifacts.</li>
 * </ol>
 *
 * <p>If Qdrant is unavailable, generation proceeds with empty context.
 */
@RestController
@RequestMapping("/api/jobs")
public class ServiceGeneratorController {

    private static final Logger log = LoggerFactory.getLogger(ServiceGeneratorController.class);
    private static final int RAG_TOP_K = 5;

    private final ServiceGeneratorAgent      serviceGeneratorAgent;
    private final ServiceBoundaryRepository  boundaryRepository;
    private final MigrationJobRepository     jobRepository;
    private final RagRetriever               ragRetriever;

    public ServiceGeneratorController(ServiceGeneratorAgent serviceGeneratorAgent,
                                      ServiceBoundaryRepository boundaryRepository,
                                      MigrationJobRepository jobRepository,
                                      RagRetriever ragRetriever) {
        this.serviceGeneratorAgent = serviceGeneratorAgent;
        this.boundaryRepository    = boundaryRepository;
        this.jobRepository         = jobRepository;
        this.ragRetriever          = ragRetriever;
    }

    @PostMapping("/{id}/generate-service/{boundaryId}")
    public ResponseEntity<?> generateService(@PathVariable Long id,
                                             @PathVariable Long boundaryId) {
        // Validate job exists
        if (!jobRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        // Load service boundary
        ServiceBoundary boundary;
        try {
            boundary = boundaryRepository.findById(boundaryId)
                    .orElseThrow(() -> new NoSuchElementException(
                            "ServiceBoundary not found: " + boundaryId));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }

        // Fetch RAG context — best-effort, empty on failure
        List<String> contextSnippets = fetchRagContext(id, boundary);

        // Generate the service
        try {
            log.info("[gen-ctrl] Generating {} for job={}", boundary.getServiceName(), id);
            ServiceGenerationResult result = serviceGeneratorAgent.generate(
                    id, boundary, contextSnippets);

            List<Map<String, String>> fileSummary = result.files().stream()
                    .map(f -> Map.of(
                            "filePath", f.filePath(),
                            "lines",    String.valueOf(f.content().lines().count())
                    ))
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "jobId",          id,
                    "boundaryId",     boundaryId,
                    "serviceName",    boundary.getServiceName(),
                    "filesGenerated", result.files().size(),
                    "artifactsStored", result.artifacts().size(),
                    "taskId",         result.task().getId(),
                    "ragSnippets",    contextSnippets.size(),
                    "files",          fileSummary
            ));
        } catch (Exception e) {
            log.error("[gen-ctrl] Failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private List<String> fetchRagContext(Long jobId, ServiceBoundary boundary) {
        String query = boundary.getDescription() != null
                ? boundary.getDescription()
                : boundary.getServiceName() + " domain entity management";
        try {
            List<CodeChunk> chunks = ragRetriever.retrieve(jobId, query, RAG_TOP_K);
            return chunks.stream()
                    .map(CodeChunk::body)
                    .filter(b -> b != null && !b.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("[gen-ctrl] RAG unavailable for job {} — generating without context: {}",
                    jobId, e.getMessage());
            return List.of();
        }
    }
}
