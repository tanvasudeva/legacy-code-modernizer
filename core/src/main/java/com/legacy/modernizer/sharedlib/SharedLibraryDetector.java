package com.legacy.modernizer.sharedlib;

import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DD2 — Shared Library Detector.
 *
 * <p>Orchestrates the full shared-class extraction pipeline for one migration job:
 * <ol>
 *   <li>Tag Neo4j Class nodes with their service boundary (temp property).</li>
 *   <li>Run cross-service CALLS query via {@link SharedLibraryAnalyzer}.</li>
 *   <li>Remove shared classes from their original ServiceBoundary rows.</li>
 *   <li>Create a new {@code {repoName}-commons} ServiceBoundary with all shared classes.</li>
 *   <li>Persist per-class records to {@code shared_classes} table.</li>
 *   <li>Return a {@link SharedLibraryPlan} describing the extraction.</li>
 * </ol>
 *
 * <p><strong>Call site:</strong> invoke from {@code BenchmarkRunner} after
 * {@code ArchitectAgent.analyze()} and before {@code ServiceGeneratorAgent.generate()}.
 * The method is idempotent — if a {@code -commons} boundary already exists it is returned
 * as-is without re-running Neo4j queries.
 */
@Component
public class SharedLibraryDetector {

    private static final Logger log = LoggerFactory.getLogger(SharedLibraryDetector.class);

    private final SharedLibraryAnalyzer       analyzer;
    private final SharedClassRepository       sharedClassRepository;
    private final ServiceBoundaryRepository   boundaryRepository;

    public SharedLibraryDetector(SharedLibraryAnalyzer analyzer,
                                  SharedClassRepository sharedClassRepository,
                                  ServiceBoundaryRepository boundaryRepository) {
        this.analyzer              = analyzer;
        this.sharedClassRepository = sharedClassRepository;
        this.boundaryRepository    = boundaryRepository;
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Detects shared classes across service boundaries for a job and extracts them
     * into a new {@code {repoName}-commons} ServiceBoundary.
     *
     * @param jobId    migration job ID
     * @param repoName benchmark repository name (used for the commons module name)
     * @return plan; {@code sharedClasses} is empty if nothing is detected
     */
    @Transactional
    public SharedLibraryPlan detect(Long jobId, String repoName) {
        String commonsName = repoName + "-commons";
        List<ServiceBoundary> boundaries = boundaryRepository.findByJobId(jobId);

        // Idempotency: return early if commons already exists
        boolean alreadyExtracted = boundaries.stream()
                .anyMatch(b -> commonsName.equals(b.getServiceName()));
        if (alreadyExtracted) {
            log.info("[shared-lib] Commons '{}' already exists for job {}, returning cached plan",
                    commonsName, jobId);
            return buildPlanFromDb(jobId, commonsName, boundaries);
        }

        // Filter out any pre-existing -commons boundaries and work only on real service boundaries
        List<ServiceBoundary> serviceBoundaries = boundaries.stream()
                .filter(b -> !b.getServiceName().endsWith("-commons"))
                .toList();

        if (serviceBoundaries.isEmpty()) {
            log.warn("[shared-lib] No service boundaries found for job {}", jobId);
            return new SharedLibraryPlan(jobId, commonsName, List.of(), 0, 0, 0.0);
        }

        // Tag Neo4j nodes with service boundary assignments
        analyzer.tagClassesWithService(serviceBoundaries);

        try {
            List<SharedClassResult> shared =
                    analyzer.findSharedClasses(analyzer.getMinServiceCount());

            if (shared.isEmpty()) {
                log.info("[shared-lib] No shared classes detected for job {} (repo={})", jobId, repoName);
                return new SharedLibraryPlan(jobId, commonsName, List.of(), 0, 0, 0.0);
            }

            Set<String> sharedFqns = shared.stream()
                    .map(SharedClassResult::fqn)
                    .collect(Collectors.toSet());

            // Count total classes before extraction for the duplication rate
            int totalClassesBeforeExtraction = serviceBoundaries.stream()
                    .mapToInt(b -> b.getClassFqns() == null ? 0 : b.getClassFqns().size())
                    .sum();

            // Remove shared classes from original service boundaries
            for (ServiceBoundary b : serviceBoundaries) {
                List<String> original = b.getClassFqns() == null ? List.of() : b.getClassFqns();
                List<String> retained = original.stream()
                        .filter(fqn -> !sharedFqns.contains(fqn))
                        .toList();
                int removed = original.size() - retained.size();
                if (removed > 0) {
                    b.setClassFqns(retained);
                    boundaryRepository.save(b);
                    log.info("[shared-lib] Removed {} shared classes from '{}'", removed, b.getServiceName());
                }
            }

            // Create the commons ServiceBoundary
            ServiceBoundary commons = ServiceBoundary.builder()
                    .jobId(jobId)
                    .serviceName(commonsName)
                    .classFqns(new ArrayList<>(sharedFqns))
                    .description("Shared utility library — classes referenced by 2+ service boundaries.")
                    .rationale("Auto-detected via Neo4j cross-service CALLS analysis: "
                             + shared.size() + " classes referenced by >= "
                             + analyzer.getMinServiceCount() + " service boundaries.")
                    .build();
            boundaryRepository.save(commons);
            log.info("[shared-lib] Created '{}' commons boundary with {} classes",
                    commonsName, shared.size());

            // Persist per-class records
            sharedClassRepository.deleteByJobId(jobId);  // clear stale records if any
            List<SharedClass> rows = shared.stream()
                    .map(r -> SharedClass.builder()
                            .jobId(jobId)
                            .classFqn(r.fqn())
                            .serviceCount(r.serviceCount())
                            .referencingServices(r.referencingServices())
                            .build())
                    .toList();
            sharedClassRepository.saveAll(rows);

            // Compute stats
            int duplicationsAvoided = shared.stream()
                    .mapToInt(r -> r.serviceCount() - 1)
                    .sum();
            double duplicationRate = totalClassesBeforeExtraction > 0
                    ? (double) sharedFqns.size() / totalClassesBeforeExtraction
                    : 0.0;

            log.info("[shared-lib] Extraction complete — {} shared, {} duplications avoided, rate={:.3f}",
                    shared.size(), duplicationsAvoided, duplicationRate);
            return new SharedLibraryPlan(jobId, commonsName, shared, shared.size(),
                    duplicationsAvoided, duplicationRate);

        } finally {
            analyzer.clearServiceTags(serviceBoundaries);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private SharedLibraryPlan buildPlanFromDb(Long jobId, String commonsName,
                                               List<ServiceBoundary> boundaries) {
        List<SharedClass> rows = sharedClassRepository.findByJobId(jobId);
        List<SharedClassResult> shared = rows.stream()
                .map(r -> new SharedClassResult(r.getClassFqn(),
                        shortName(r.getClassFqn()),
                        r.getServiceCount(),
                        r.getReferencingServices() != null ? r.getReferencingServices() : List.of()))
                .toList();
        int total = boundaries.stream()
                .mapToInt(b -> b.getClassFqns() == null ? 0 : b.getClassFqns().size())
                .sum();
        int dups = shared.stream().mapToInt(r -> r.serviceCount() - 1).sum();
        double rate = total > 0 ? (double) rows.size() / total : 0.0;
        return new SharedLibraryPlan(jobId, commonsName, shared, rows.size(), dups, rate);
    }

    private static String shortName(String fqn) {
        if (fqn == null || fqn.isBlank()) return fqn;
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
