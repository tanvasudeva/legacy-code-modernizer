package com.legacy.modernizer.controller;

import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.ArtifactRepository;
import com.legacy.modernizer.repository.MigrationJobRepository;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Dashboard-facing endpoints for service boundaries and generated artifacts.
 *
 * <pre>
 * GET /api/jobs/{id}/boundaries                — service boundary list for a job
 * GET /api/jobs/{id}/artifacts/{serviceName}   — artifacts for one service (file tree)
 * </pre>
 */
@RestController
@RequestMapping("/api/jobs")
public class BoundaryController {

    private final ServiceBoundaryRepository boundaryRepository;
    private final ArtifactRepository        artifactRepository;
    private final MigrationJobRepository    jobRepository;

    public BoundaryController(ServiceBoundaryRepository boundaryRepository,
                               ArtifactRepository artifactRepository,
                               MigrationJobRepository jobRepository) {
        this.boundaryRepository = boundaryRepository;
        this.artifactRepository = artifactRepository;
        this.jobRepository      = jobRepository;
    }

    /** Lists all service boundaries for a job (service name, class count, description). */
    @GetMapping("/{id}/boundaries")
    public ResponseEntity<?> listBoundaries(@PathVariable Long id) {
        if (!jobRepository.existsById(id)) return ResponseEntity.notFound().build();
        List<ServiceBoundary> boundaries = boundaryRepository.findByJobId(id);
        List<Map<String, Object>> body = boundaries.stream().map(b -> Map.<String, Object>of(
                "id",          b.getId(),
                "serviceName", b.getServiceName(),
                "classCount",  b.getClassFqns() == null ? 0 : b.getClassFqns().size(),
                "classFqns",   b.getClassFqns() == null ? List.of() : b.getClassFqns(),
                "description", b.getDescription() != null ? b.getDescription() : "",
                "rationale",   b.getRationale()   != null ? b.getRationale()   : "",
                "isCommons",   b.getServiceName().endsWith("-commons")
        )).toList();
        return ResponseEntity.ok(body);
    }

    /**
     * Returns all SERVICE_CODE artifacts for one service, grouped by file path.
     * Each entry contains the path, artifact type, and content for CodeViewer.
     */
    @GetMapping("/{id}/artifacts/{serviceName}")
    public ResponseEntity<?> listArtifacts(@PathVariable Long id,
                                            @PathVariable String serviceName) {
        if (!jobRepository.existsById(id)) return ResponseEntity.notFound().build();
        List<Artifact> artifacts = artifactRepository.findByJobIdAndClassFqn(id, serviceName);
        List<Map<String, Object>> body = artifacts.stream()
                .filter(a -> a.getFilePath() != null && a.getContent() != null)
                .filter(a -> a.getArtifactType() == ArtifactType.SERVICE_CODE
                          || a.getArtifactType() == ArtifactType.TEST_CODE)
                .sorted((a, b) -> {
                    // pom.xml first, then src/ tree alphabetically
                    boolean aPom = "pom.xml".equals(a.getFilePath());
                    boolean bPom = "pom.xml".equals(b.getFilePath());
                    if (aPom != bPom) return aPom ? -1 : 1;
                    return a.getFilePath().compareTo(b.getFilePath());
                })
                .map(a -> Map.<String, Object>of(
                        "id",           a.getId(),
                        "filePath",     a.getFilePath(),
                        "artifactType", a.getArtifactType().name(),
                        "content",      a.getContent(),
                        "lineCount",    a.getContent().lines().count()
                ))
                .collect(Collectors.toList());
        return ResponseEntity.ok(body);
    }
}
