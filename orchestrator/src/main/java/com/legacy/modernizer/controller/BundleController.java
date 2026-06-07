package com.legacy.modernizer.controller;

import com.legacy.modernizer.bundle.BundleAssembler;
import com.legacy.modernizer.bundle.BundleManifest;
import com.legacy.modernizer.repository.MigrationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;

/**
 * GET /api/jobs/{id}/bundle
 *
 * <p>Streams a ZIP archive containing all generated artefacts for the job:
 * <ul>
 *   <li>Root {@code pom.xml} — multi-module Maven wrapper referencing every service</li>
 *   <li>Root {@code docker-compose.yml} — one service block per microservice</li>
 *   <li>{@code <service-name>/} directory trees reconstructed from artifact file_path</li>
 * </ul>
 *
 * <p>The response sets {@code Content-Disposition: attachment} so browsers trigger
 * a file-save dialog.  The ZIP is built in-memory before being written, so it is
 * not suitable for bundles larger than available heap (Phase 4 can add streaming).
 */
@RestController
@RequestMapping("/api/jobs")
public class BundleController {

    private static final Logger log = LoggerFactory.getLogger(BundleController.class);

    private final BundleAssembler        bundleAssembler;
    private final MigrationJobRepository jobRepository;

    public BundleController(BundleAssembler bundleAssembler,
                            MigrationJobRepository jobRepository) {
        this.bundleAssembler = bundleAssembler;
        this.jobRepository   = jobRepository;
    }

    @GetMapping("/{id}/bundle")
    public ResponseEntity<Resource> downloadBundle(@PathVariable Long id) {
        if (!jobRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            BundleManifest manifest   = bundleAssembler.assemble(id, buf);
            byte[] bytes              = buf.toByteArray();

            log.info("[bundle-ctrl] Job {} — {} bytes, {} files, services={}",
                    id, bytes.length, manifest.fileCount(), manifest.serviceNames());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"lcm-job-" + id + "-bundle.zip\"")
                    .contentLength(bytes.length)
                    .body(new ByteArrayResource(bytes));

        } catch (Exception e) {
            log.error("[bundle-ctrl] Failed for job {}: {}", id, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
