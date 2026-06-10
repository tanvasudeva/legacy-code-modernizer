package com.legacy.modernizer.controller;

import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.MigrationJobRepository;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import com.legacy.modernizer.sharedlib.SharedClass;
import com.legacy.modernizer.sharedlib.SharedClassRepository;
import com.legacy.modernizer.sharedlib.SharedClassResult;
import com.legacy.modernizer.sharedlib.SharedLibraryPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * DD2 — Shared Library endpoint.
 *
 * <pre>
 * GET /api/jobs/{id}/shared-classes   — SharedLibraryPlan JSON for one job
 * </pre>
 *
 * <p>Returns 404 if no commons ServiceBoundary exists (i.e. shared-library detection
 * has not been run for this job yet).
 */
@RestController
@RequestMapping("/api/jobs")
public class SharedClassesController {

    private static final Logger log = LoggerFactory.getLogger(SharedClassesController.class);

    private final SharedClassRepository     sharedClassRepository;
    private final ServiceBoundaryRepository boundaryRepository;
    private final MigrationJobRepository    jobRepository;

    public SharedClassesController(SharedClassRepository     sharedClassRepository,
                                    ServiceBoundaryRepository boundaryRepository,
                                    MigrationJobRepository    jobRepository) {
        this.sharedClassRepository = sharedClassRepository;
        this.boundaryRepository    = boundaryRepository;
        this.jobRepository         = jobRepository;
    }

    /**
     * Returns the shared-library extraction plan for a job.
     *
     * <p>Reconstructs the {@link SharedLibraryPlan} from the {@code shared_classes}
     * table and {@code service_boundaries} rather than re-running Neo4j queries.
     *
     * @return 200 + SharedLibraryPlan JSON, or 404 if detection has not been run
     */
    @GetMapping("/{id}/shared-classes")
    public ResponseEntity<SharedLibraryPlan> sharedClasses(@PathVariable Long id) {
        if (!jobRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        List<ServiceBoundary> allBoundaries = boundaryRepository.findByJobId(id);

        Optional<ServiceBoundary> commonsBoundary = allBoundaries.stream()
                .filter(b -> b.getServiceName().endsWith("-commons"))
                .findFirst();

        if (commonsBoundary.isEmpty()) {
            log.info("[shared-classes] No commons boundary for job {} — detection not run", id);
            return ResponseEntity.notFound().build();
        }

        String commonsName = commonsBoundary.get().getServiceName();
        List<SharedClass> rows = sharedClassRepository.findByJobId(id);

        List<SharedClassResult> sharedResults = rows.stream()
                .map(r -> new SharedClassResult(
                        r.getClassFqn(),
                        shortName(r.getClassFqn()),
                        r.getServiceCount(),
                        r.getReferencingServices() != null ? r.getReferencingServices() : List.of()))
                .toList();

        // Total classes = service boundaries (excluding commons) + commons classes
        int totalClasses = allBoundaries.stream()
                .filter(b -> !b.getServiceName().endsWith("-commons"))
                .mapToInt(b -> b.getClassFqns() == null ? 0 : b.getClassFqns().size())
                .sum() + sharedResults.size();

        int duplicationsAvoided = sharedResults.stream()
                .mapToInt(r -> r.serviceCount() - 1)
                .sum();
        double duplicationRate = totalClasses > 0
                ? (double) sharedResults.size() / totalClasses
                : 0.0;

        SharedLibraryPlan plan = new SharedLibraryPlan(
                id, commonsName, sharedResults,
                sharedResults.size(), duplicationsAvoided, duplicationRate);

        log.info("[shared-classes] job {} — {} shared classes, {} duplications avoided",
                id, sharedResults.size(), duplicationsAvoided);
        return ResponseEntity.ok(plan);
    }

    private static String shortName(String fqn) {
        if (fqn == null || fqn.isBlank()) return fqn;
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
