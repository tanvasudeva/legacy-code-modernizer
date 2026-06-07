package com.legacy.modernizer.controller;

import com.legacy.modernizer.model.MigrationJob;
import com.legacy.modernizer.rag.RagIndexService;
import com.legacy.modernizer.repository.MigrationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * POST /api/jobs/{id}/index-rag
 *
 * <p>Triggers the RAG indexing pipeline for the job's source directory:
 * method chunking → CodeBERT embedding → Qdrant upsert.
 * Returns the number of method chunks indexed.
 */
@RestController
@RequestMapping("/api/jobs")
public class RagIndexController {

    private static final Logger log = LoggerFactory.getLogger(RagIndexController.class);

    private final RagIndexService           ragIndexService;
    private final MigrationJobRepository    jobRepository;

    public RagIndexController(RagIndexService ragIndexService,
                              MigrationJobRepository jobRepository) {
        this.ragIndexService = ragIndexService;
        this.jobRepository   = jobRepository;
    }

    @PostMapping("/{id}/index-rag")
    public ResponseEntity<?> indexRag(@PathVariable Long id) {
        MigrationJob job;
        try {
            job = jobRepository.findById(id)
                    .orElseThrow(() -> new NoSuchElementException("Job not found: " + id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }

        try {
            log.info("[rag-index] Starting for job={} dir={}", id, job.getSourceDirectory());
            int count = ragIndexService.index(id, job.getSourceDirectory());
            return ResponseEntity.ok(Map.of(
                    "jobId",         id,
                    "collection",    "job_" + id,
                    "chunksIndexed", count
            ));
        } catch (Exception e) {
            log.error("[rag-index] Failed job={}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
