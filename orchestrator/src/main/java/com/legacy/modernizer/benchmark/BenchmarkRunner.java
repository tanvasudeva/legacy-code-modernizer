package com.legacy.modernizer.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.legacy.modernizer.agent.ArchitectAgent;
import com.legacy.modernizer.agent.DocGenAgent;
import com.legacy.modernizer.agent.ServiceGeneratorAgent;
import com.legacy.modernizer.agent.TestWriterAgent;
import com.legacy.modernizer.bundle.BundleAssembler;
import com.legacy.modernizer.bundle.BundleManifest;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.JobStatus;
import com.legacy.modernizer.model.MigrationJob;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.rag.CodeChunk;
import com.legacy.modernizer.rag.RagIndexService;
import com.legacy.modernizer.rag.RagRetriever;
import com.legacy.modernizer.repository.ArtifactRepository;
import com.legacy.modernizer.repository.MigrationJobRepository;
import com.legacy.modernizer.repository.ServiceBoundaryRepository;
import com.legacy.modernizer.service.AnalysisService;
import com.legacy.modernizer.dto.AnalysisResult;
import com.legacy.modernizer.sharedlib.SharedLibraryDetector;
import com.legacy.modernizer.sharedlib.SharedLibraryPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 4.1 — Benchmark Runner.
 *
 * <p>Orchestrates the full multi-agent pipeline for a single benchmark repository,
 * persists every intermediate artifact to PostgreSQL, assembles the ZIP bundle, and
 * writes the bundle + a {@code metrics.json} summary to the results directory.
 *
 * <p>Pipeline steps per repo:
 * <ol>
 *   <li>Create a {@link MigrationJob} in PENDING state.</li>
 *   <li>Run static analysis — {@link AnalysisService#analyze(Long)} → cluster map.</li>
 *   <li>Decompose into DDD boundaries — {@link ArchitectAgent#analyze(Long, Map)}.</li>
 *   <li>For each boundary:
 *     <ol>
 *       <li>Fetch top-5 RAG snippets.</li>
 *       <li>Generate service code — {@link ServiceGeneratorAgent#generate}.</li>
 *       <li>Generate tests — {@link TestWriterAgent#generate}.</li>
 *       <li>Generate docs — {@link DocGenAgent#generate}.</li>
 *     </ol>
 *   </li>
 *   <li>Assemble ZIP bundle — {@link BundleAssembler#assemble}.</li>
 *   <li>Write {@code results/{repoName}/multi-agent/bundle.zip} and {@code metrics.json}.</li>
 * </ol>
 */
@Component
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private final AnalysisService           analysisService;
    private final ArchitectAgent            architectAgent;
    private final SharedLibraryDetector     sharedLibraryDetector;
    private final ServiceGeneratorAgent     serviceGeneratorAgent;
    private final TestWriterAgent           testWriterAgent;
    private final DocGenAgent               docGenAgent;
    private final BundleAssembler           bundleAssembler;
    private final RagIndexService            ragIndexService;
    private final RagRetriever              ragRetriever;
    private final ArtifactRepository        artifactRepository;
    private final ServiceBoundaryRepository boundaryRepository;
    private final MigrationJobRepository    jobRepository;

    @Value("${benchmark.results.dir:results}")
    private String resultsDirProp;

    @Value("${benchmark.rag-top-k:5}")
    private int ragTopK;

    @Value("${benchmark.skip-docs:false}")
    private boolean skipDocs;

    public BenchmarkRunner(AnalysisService analysisService,
                           ArchitectAgent architectAgent,
                           SharedLibraryDetector sharedLibraryDetector,
                           ServiceGeneratorAgent serviceGeneratorAgent,
                           TestWriterAgent testWriterAgent,
                           DocGenAgent docGenAgent,
                           BundleAssembler bundleAssembler,
                           RagIndexService ragIndexService,
                           RagRetriever ragRetriever,
                           ArtifactRepository artifactRepository,
                           ServiceBoundaryRepository boundaryRepository,
                           MigrationJobRepository jobRepository) {
        this.analysisService       = analysisService;
        this.architectAgent        = architectAgent;
        this.sharedLibraryDetector = sharedLibraryDetector;
        this.serviceGeneratorAgent = serviceGeneratorAgent;
        this.testWriterAgent       = testWriterAgent;
        this.docGenAgent           = docGenAgent;
        this.bundleAssembler       = bundleAssembler;
        this.ragIndexService       = ragIndexService;
        this.ragRetriever          = ragRetriever;
        this.artifactRepository    = artifactRepository;
        this.boundaryRepository    = boundaryRepository;
        this.jobRepository         = jobRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the full pipeline for one benchmark repository.
     *
     * @param spec       the benchmark target (name + source directory)
     * @param projectRoot absolute path to the LCM project root (for resolving relative src paths)
     * @return result record with metrics; {@link BenchmarkResult#errorMessage()} is non-null on failure
     */
    public BenchmarkResult run(BenchmarkSpec spec, Path projectRoot) {
        long start = System.currentTimeMillis();
        String repoName = spec.name();
        log.info("[benchmark] ▶ Starting {} (~{} LOC)", repoName, spec.approxLoc());

        Path srcDir = spec.resolveSrc(projectRoot);
        if (!Files.isDirectory(srcDir)) {
            String msg = "Source directory not found: " + srcDir;
            log.error("[benchmark] {}", msg);
            return failed(repoName, null, msg, start);
        }

        MigrationJob job = jobRepository.save(MigrationJob.builder()
                .name("benchmark-" + repoName)
                .sourceDirectory(srcDir.toAbsolutePath().toString())
                .status(JobStatus.PENDING)
                .build());
        Long jobId = job.getId();
        log.info("[benchmark] Created job {} for {}", jobId, repoName);

        try {
            // Step 1: Static analysis + Louvain
            log.info("[benchmark][{}] Step 1/5 — static analysis", repoName);
            AnalysisResult analysis = analysisService.analyze(jobId);
            Map<String, String> clusterMap = analysis.clusterMap();
            log.info("[benchmark][{}] {} classes, {} clusters",
                    repoName, analysis.classNodes(), analysis.clusterCount());

            // Step 1b: RAG indexing (must happen before generation so snippets are available)
            log.info("[benchmark][{}] Step 1b/5 — RAG indexing", repoName);
            try {
                int chunks = ragIndexService.index(jobId, srcDir.toAbsolutePath().toString());
                log.info("[benchmark][{}] RAG indexed {} chunks", repoName, chunks);
            } catch (Exception e) {
                log.warn("[benchmark][{}] RAG indexing failed (will proceed without RAG): {}", repoName, e.getMessage());
            }

            // Step 2: DDD boundaries
            log.info("[benchmark][{}] Step 2/5 — architect agent", repoName);
            architectAgent.analyze(jobId, clusterMap, analysis.interClusterCalls());

            // Step 2b: DD2 — shared library detection (runs after ArchitectAgent, before generation)
            log.info("[benchmark][{}] Step 2b/5 — shared library detection", repoName);
            SharedLibraryPlan sharedPlan = sharedLibraryDetector.detect(jobId, repoName);
            if (sharedPlan.totalSharedClasses() > 0) {
                log.info("[benchmark][{}] {} shared classes → commons module '{}'",
                        repoName, sharedPlan.totalSharedClasses(), sharedPlan.commonsModuleName());
            }

            // Re-fetch boundaries (includes the new commons boundary if one was created)
            List<ServiceBoundary> boundaries = boundaryRepository.findByJobId(jobId)
                    .stream()
                    .sorted((a, b) -> {
                        // Generate commons first so other services can compile against it
                        boolean aCommons = a.getServiceName().endsWith("-commons");
                        boolean bCommons = b.getServiceName().endsWith("-commons");
                        if (aCommons == bCommons) return 0;
                        return aCommons ? -1 : 1;
                    })
                    .toList();
            log.info("[benchmark][{}] {} service boundaries (incl. commons)", repoName, boundaries.size());

            // Step 3: Generate per-boundary
            int totalFiles  = 0;
            int totalTokens = 0;
            List<String> serviceNames = new ArrayList<>();

            for (ServiceBoundary boundary : boundaries) {
                String svcName = boundary.getServiceName();
                serviceNames.add(svcName);
                log.info("[benchmark][{}] Step 3/5 — generating {}", repoName, svcName);

                List<String> ragSnippets = fetchRag(jobId, boundary, ragTopK);

                // 3a. Service code
                var svcResult = serviceGeneratorAgent.generate(jobId, boundary, ragSnippets, srcDir);
                totalFiles  += svcResult.files().size();
                totalTokens += tokenCount(svcResult.task().getTokensUsed());

                // 3b. Tests (best-effort — skip if service gen produced no files)
                if (!svcResult.files().isEmpty()) {
                    try {
                        String pkg        = toPackage(svcName);
                        String entityName = extractEntityName(svcResult.files().stream()
                                .map(f -> f.filePath()).toList());
                        List<Artifact> svcArtifacts = artifactRepository
                                .findByJobIdAndClassFqn(jobId, svcName)
                                .stream().filter(a -> a.getArtifactType() == ArtifactType.SERVICE_CODE)
                                .toList();

                        var testResult = testWriterAgent.generate(
                                jobId, svcName, pkg, entityName,
                                findContent(svcArtifacts, "Service.java"),
                                findContent(svcArtifacts, "Controller.java"),
                                findContent(svcArtifacts, "entity/"),
                                findContent(svcArtifacts, "pom.xml"),
                                ragSnippets);
                        totalFiles  += testResult.files().size();
                        totalTokens += tokenCount(testResult.task().getTokensUsed());

                        // 3c. Docs — skipped in lite mode (contributes to no eval metric)
                        if (!skipDocs) {
                            var docResult = docGenAgent.generate(
                                    jobId, boundary, pkg, entityName,
                                    findContent(svcArtifacts, "Controller.java"),
                                    findContent(svcArtifacts, "Service.java"),
                                    findContent(svcArtifacts, "entity/"),
                                    ragSnippets);
                            totalFiles  += docResult.files().size();
                            totalTokens += tokenCount(docResult.task().getTokensUsed());
                        }

                    } catch (Exception e) {
                        log.warn("[benchmark][{}] Test/doc gen failed for {}: {}",
                                repoName, svcName, e.getMessage());
                    }
                }
            }

            // Step 4: Bundle
            log.info("[benchmark][{}] Step 4/5 — assembling bundle", repoName);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            BundleManifest manifest = bundleAssembler.assemble(jobId, buf);
            byte[] zipBytes = buf.toByteArray();

            // Step 5: Save to results/
            log.info("[benchmark][{}] Step 5/5 — writing results ({} bytes)", repoName, zipBytes.length);
            Path outDir = saveResults(repoName, jobId, zipBytes, manifest, totalFiles,
                    totalTokens, boundaries.size(), boundaries, System.currentTimeMillis() - start);

            long elapsed = System.currentTimeMillis() - start;
            log.info("[benchmark] ✓ {} done in {}s — {} services, {} files, {} tokens",
                    repoName, elapsed / 1000, boundaries.size(), totalFiles, totalTokens);

            return new BenchmarkResult(
                    repoName, jobId, boundaries.size(), totalFiles, totalTokens,
                    elapsed, true, outDir.resolve("bundle.zip").toString(),
                    serviceNames, null, LocalDateTime.now());

        } catch (Exception e) {
            log.error("[benchmark] ✗ {} failed: {}", repoName, e.getMessage(), e);
            return failed(repoName, jobId, e.getMessage(), start);
        }
    }

    // -------------------------------------------------------------------------
    // Results persistence
    // -------------------------------------------------------------------------

    private Path saveResults(String repoName, Long jobId, byte[] zipBytes,
                             BundleManifest manifest, int totalFiles, int totalTokens,
                             int serviceCount, List<ServiceBoundary> boundaries,
                             long elapsedMs) throws IOException {
        Path outDir = Path.of(resultsDirProp)
                .resolve(repoName)
                .resolve("multi-agent")
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(outDir);

        // bundle.zip
        Files.write(outDir.resolve("bundle.zip"), zipBytes);

        // metrics.json
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        Map<String, Object> metrics = Map.of(
                "repoName",    repoName,
                "jobId",       jobId,
                "serviceCount", serviceCount,
                "serviceNames", manifest.serviceNames(),
                "totalFilesGenerated", totalFiles,
                "totalTokensUsed", totalTokens,
                "bundleSizeBytes", zipBytes.length,
                "elapsedMs",   elapsedMs,
                "completedAt", LocalDateTime.now().toString(),
                "boundaries",  boundaries.stream().map(b -> Map.of(
                        "name",       b.getServiceName(),
                        "classCount", b.getClassFqns().size()
                )).toList()
        );
        om.writerWithDefaultPrettyPrinter().writeValue(outDir.resolve("metrics.json").toFile(), metrics);

        // placeholder dirs for baseline systems (populated in Phase 4.2)
        Files.createDirectories(Path.of(resultsDirProp).resolve(repoName).resolve("single-prompt-claude"));
        Files.createDirectories(Path.of(resultsDirProp).resolve(repoName).resolve("single-prompt-gpt4o"));

        return outDir;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<String> fetchRag(Long jobId, ServiceBoundary boundary, int topK) {
        String query = boundary.getDescription() != null
                ? boundary.getDescription()
                : boundary.getServiceName() + " domain entity";
        try {
            return ragRetriever.retrieve(jobId, query, topK)
                    .stream().map(CodeChunk::body)
                    .filter(b -> b != null && !b.isBlank())
                    .toList();
        } catch (Exception e) {
            log.warn("[benchmark] RAG unavailable for job {}: {}", jobId, e.getMessage());
            return List.of();
        }
    }

    private static String findContent(List<Artifact> artifacts, String pathSuffix) {
        return artifacts.stream()
                .filter(a -> a.getFilePath() != null && a.getFilePath().contains(pathSuffix))
                .map(Artifact::getContent)
                .findFirst()
                .orElse("");
    }

    private static String extractEntityName(List<String> filePaths) {
        return filePaths.stream()
                .filter(p -> p.contains("/controller/") && p.endsWith("Controller.java"))
                .map(p -> p.substring(p.lastIndexOf('/') + 1).replace("Controller.java", ""))
                .findFirst()
                .orElse("Entity");
    }

    private static String toPackage(String serviceName) {
        return serviceName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    private static int tokenCount(Integer t) { return t != null ? t : 0; }

    private static BenchmarkResult failed(String repoName, Long jobId, String msg, long start) {
        return new BenchmarkResult(repoName, jobId, 0, 0, 0,
                System.currentTimeMillis() - start, false, null,
                List.of(), msg, LocalDateTime.now());
    }
}
