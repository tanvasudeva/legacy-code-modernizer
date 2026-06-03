package com.legacy.modernizer.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Retrieves the top-K most semantically similar method chunks for a query.
 *
 * <p>Flow:
 * <ol>
 *   <li>Calls {@code embedder.py --text "<query>"} subprocess to get a 768-dim
 *       CodeBERT vector — same embedding space as the indexed chunks.</li>
 *   <li>POSTs to Qdrant REST API:
 *       {@code /collections/job_{id}/points/search}</li>
 *   <li>Deserialises results into {@link CodeChunk} records.</li>
 * </ol>
 */
@Component
public class RagRetriever {

    private static final Logger log = LoggerFactory.getLogger(RagRetriever.class);
    private static final int DEFAULT_TOP_K = 5;

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient = HttpClient.newHttpClient();

    @Value("${analysis.python.executable:/opt/homebrew/bin/python3.11}")
    private String pythonExec;

    @Value("${analysis.scripts.dir:../scripts}")
    private String scriptsDir;

    @Value("${qdrant.url:http://localhost:6333}")
    private String qdrantUrl;

    public RagRetriever(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Returns up to {@code topK} method chunks most relevant to {@code query}
     * from the Qdrant collection for the given job.
     */
    public List<CodeChunk> retrieve(Long jobId, String query, int topK) {
        log.debug("[rag] retrieve job={} topK={} query=\"{}\"", jobId, topK, query);
        double[] queryVector = embedQuery(query);
        return searchQdrant(jobId, queryVector, topK);
    }

    public List<CodeChunk> retrieve(Long jobId, String query) {
        return retrieve(jobId, query, DEFAULT_TOP_K);
    }

    // -----------------------------------------------------------------
    // Query embedding via embedder.py subprocess
    // -----------------------------------------------------------------

    // package-private for unit tests
    double[] embedQuery(String query) {
        Path script = Path.of(scriptsDir).toAbsolutePath().normalize().resolve("embedder.py");
        try {
            Process process = new ProcessBuilder(pythonExec, script.toString(), "--text", query)
                    .start();
            // Drain stderr asynchronously to avoid blocking the process
            Thread stderrDrainer = new Thread(() -> {
                try { process.getErrorStream().transferTo(OutputStream.nullOutputStream()); }
                catch (IOException ignored) {}
            });
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            String vectorJson = new String(process.getInputStream().readAllBytes());
            process.waitFor();

            double[] vector = objectMapper.readValue(vectorJson, double[].class);
            log.debug("[rag] embedded query → {} dims", vector.length);
            return vector;
        } catch (Exception e) {
            throw new RuntimeException("Failed to embed query via embedder.py: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------
    // Qdrant search via HTTP
    // -----------------------------------------------------------------

    // package-private for unit tests
    List<CodeChunk> searchQdrant(Long jobId, double[] queryVector, int topK) {
        String url = qdrantUrl + "/collections/job_" + jobId + "/points/search";
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "vector",       queryVector,
                    "limit",        topK,
                    "with_payload", true
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Qdrant returned HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return parseSearchResponse(response.body());
        } catch (Exception e) {
            throw new RuntimeException("Qdrant search failed for job " + jobId + ": " + e.getMessage(), e);
        }
    }

    // package-private for unit tests
    List<CodeChunk> parseSearchResponse(String responseBody) throws Exception {
        JsonNode root    = objectMapper.readTree(responseBody);
        JsonNode results = root.path("result");
        List<CodeChunk> chunks = new ArrayList<>();

        for (JsonNode hit : results) {
            float    score   = (float) hit.path("score").asDouble(0);
            JsonNode payload = hit.path("payload");
            CodeChunk chunk  = new CodeChunk(
                    payload.path("class_fqn").asText(null),
                    payload.path("method_name").asText(null),
                    payload.path("signature").asText(null),
                    payload.path("body").asText(null),
                    payload.path("file_path").asText(null),
                    score
            );
            chunks.add(chunk);
        }
        log.debug("[rag] search returned {} chunks", chunks.size());
        return chunks;
    }
}
