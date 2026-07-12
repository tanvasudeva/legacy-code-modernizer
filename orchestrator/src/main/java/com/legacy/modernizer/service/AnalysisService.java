package com.legacy.modernizer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.dto.AnalysisResult;
import com.legacy.modernizer.extractor.DependencyExtractor;
import com.legacy.modernizer.model.DependencyGraph;
import com.legacy.modernizer.model.JobStatus;
import com.legacy.modernizer.model.MigrationJob;
import com.legacy.modernizer.neo4j.GraphIngester;
import com.legacy.modernizer.neo4j.IngestionStats;
import com.legacy.modernizer.repository.MigrationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final GraphIngester graphIngester;
    private final MigrationJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final DependencyExtractor dependencyExtractor = new DependencyExtractor();

    @Value("${analysis.python.executable:/opt/homebrew/bin/python3.11}")
    private String pythonExec;

    @Value("${analysis.scripts.dir:../scripts}")
    private String scriptsDir;

    @Value("${analysis.louvain.use-llm:false}")
    private boolean useLlm;

    /** Default algorithm — overridable per-run via {@link #analyze(Long, String)}. */
    @Value("${analysis.clustering.algorithm:louvain}")
    private String defaultAlgorithm;

    public AnalysisService(GraphIngester graphIngester,
                           MigrationJobRepository jobRepository,
                           ObjectMapper objectMapper) {
        this.graphIngester = graphIngester;
        this.jobRepository = jobRepository;
        this.objectMapper  = objectMapper;
    }

    /** Runs analysis with the configured default clustering algorithm. */
    public AnalysisResult analyze(Long jobId) {
        return analyze(jobId, null);
    }

    /**
     * Runs analysis with an explicit algorithm choice.
     *
     * @param algorithmOverride {@code "louvain"}, {@code "leiden"}, or {@code null} to use the
     *                          configured default ({@code analysis.clustering.algorithm}).
     */
    public AnalysisResult analyze(Long jobId, String algorithmOverride) {
        String algorithm = (algorithmOverride != null && !algorithmOverride.isBlank())
                ? algorithmOverride.toLowerCase() : defaultAlgorithm;

        MigrationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Job not found: " + jobId));

        job.setStatus(JobStatus.ANALYZING);
        jobRepository.save(job);

        try {
            Path sourceDir = Path.of(job.getSourceDirectory());
            log.info("[job={}] Extracting from {}", jobId, sourceDir);
            DependencyGraph graph = dependencyExtractor.extract(sourceDir);

            log.info("[job={}] Clearing previous Neo4j graph …", jobId);
            graphIngester.clearGraph();

            log.info("[job={}] Ingesting into Neo4j …", jobId);
            IngestionStats stats = graphIngester.ingest(graph);
            log.info("[job={}] {}", jobId, stats);

            String adjacencyJson = graphIngester.exportAdjacencyListJson();
            Path adjacencyFile  = Files.createTempFile("lcm-adj-"  + jobId + "-", ".json");
            Path clusterMapFile = Files.createTempFile("lcm-cmap-" + jobId + "-", ".json");
            Files.writeString(adjacencyFile, adjacencyJson);

            log.info("[job={}] Running {} clustering …", jobId, algorithm);
            ClusteringResult clustering = runClustering(adjacencyFile, clusterMapFile, algorithm);
            log.info("[job={}] {} → modularity={}, {} clusters",
                    jobId, clustering.algorithm(), clustering.modularity(), clustering.clusterCount());

            log.info("[job={}] Computing inter-cluster call edges …", jobId);
            Map<String, Map<String, Integer>> interClusterCalls =
                    graphIngester.computeInterClusterEdges(clustering.clusterMap());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("clusterMap",          clustering.clusterMap());
            metadata.put("clusteringAlgorithm", clustering.algorithm());
            metadata.put("modularity",          clustering.modularity());
            metadata.put("clusterCount",        clustering.clusterCount());
            metadata.put("resolution",          clustering.resolution());
            metadata.put("stats", Map.of(
                    "classNodes",    stats.classNodes(),
                    "packageNodes",  stats.packageNodes(),
                    "relationships", stats.relationships()));
            job.setMetadata(metadata);
            job.setStatus(JobStatus.DONE);
            jobRepository.save(job);

            Files.deleteIfExists(adjacencyFile);
            Files.deleteIfExists(clusterMapFile);

            return new AnalysisResult(jobId, "DONE",
                    stats.classNodes(), stats.packageNodes(), stats.relationships(),
                    (int) clustering.clusterMap().values().stream().distinct().count(),
                    clustering.clusterMap(), interClusterCalls);

        } catch (Exception e) {
            log.error("[job={}] Analysis failed: {}", jobId, e.getMessage(), e);
            job.setStatus(JobStatus.FAILED);
            jobRepository.save(job);
            throw new RuntimeException("Analysis failed for job " + jobId + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private record ClusteringResult(
            Map<String, String> clusterMap,
            String algorithm,
            double modularity,
            int    clusterCount,
            double resolution
    ) {}

    @SuppressWarnings("unchecked")
    private ClusteringResult runClustering(Path adjacencyFile, Path clusterMapFile,
                                           String algorithm) throws Exception {
        Path script = Path.of(scriptsDir).toAbsolutePath().normalize().resolve("louvain_cluster.py");
        List<String> cmd = new ArrayList<>(List.of(
                pythonExec, script.toString(),
                "--input",     adjacencyFile.toAbsolutePath().toString(),
                "--output",    clusterMapFile.toAbsolutePath().toString(),
                "--algorithm", algorithm));
        if (!useLlm) cmd.add("--no-llm");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output   = new String(process.getInputStream().readAllBytes());
        int exitCode    = process.waitFor();

        if (exitCode != 0)
            throw new RuntimeException("louvain_cluster.py exited " + exitCode + ":\n" + output);

        // Parse the CLUSTERING_STATS line emitted by the script
        double modularity   = 0.0;
        int    clusterCount = 0;
        double resolution   = 1.0;
        for (String line : output.split("\n")) {
            if (line.startsWith("CLUSTERING_STATS:")) {
                try {
                    Map<String, Object> s = objectMapper.readValue(
                            line.substring("CLUSTERING_STATS:".length()), Map.class);
                    modularity   = ((Number) s.getOrDefault("modularity",   0.0)).doubleValue();
                    clusterCount = ((Number) s.getOrDefault("clusterCount", 0)).intValue();
                    resolution   = ((Number) s.getOrDefault("resolution",   1.0)).doubleValue();
                } catch (Exception ex) {
                    log.warn("[clustering] Could not parse CLUSTERING_STATS: {}", ex.getMessage());
                }
                break;
            }
        }

        Map<String, String> clusterMap = objectMapper.readValue(clusterMapFile.toFile(), Map.class);
        return new ClusteringResult(clusterMap, algorithm, modularity, clusterCount, resolution);
    }
}
