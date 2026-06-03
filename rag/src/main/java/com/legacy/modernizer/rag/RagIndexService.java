package com.legacy.modernizer.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls {@code qdrant_indexer.py} as a subprocess to chunk, embed, and index
 * a job's Java source tree into a per-job Qdrant collection.
 *
 * <p>The Python script outputs a single JSON line:
 * {@code {"status":"ok","collection":"job_1","points_indexed":79}}
 */
@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);

    private final ObjectMapper objectMapper;

    @Value("${analysis.python.executable:/opt/homebrew/bin/python3.11}")
    private String pythonExec;

    @Value("${analysis.scripts.dir:../scripts}")
    private String scriptsDir;

    @Value("${qdrant.url:http://localhost:6333}")
    private String qdrantUrl;

    public RagIndexService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Indexes all Java methods under {@code sourceDirectory} into Qdrant
     * collection {@code job_{jobId}}.
     *
     * @return number of method chunks indexed
     */
    public int index(Long jobId, String sourceDirectory) throws Exception {
        Path script = Path.of(scriptsDir).toAbsolutePath().normalize()
                .resolve("qdrant_indexer.py");
        List<String> cmd = new ArrayList<>(List.of(
                pythonExec, script.toString(),
                "--job-id",     String.valueOf(jobId),
                "--source-dir", sourceDirectory,
                "--qdrant-url", qdrantUrl
        ));

        log.info("[rag-index] job={} source={}", jobId, sourceDirectory);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);  // merge stderr into stdout for logging
        Process process = pb.start();
        String output   = new String(process.getInputStream().readAllBytes());
        int exitCode    = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("qdrant_indexer.py exited " + exitCode + ":\n" + output);
        }

        // The last non-empty line is the JSON summary
        String jsonLine = lastNonEmptyLine(output);
        log.info("[rag-index] job={} result={}", jobId, jsonLine);

        JsonNode result = objectMapper.readTree(jsonLine);
        return result.path("points_indexed").asInt(0);
    }

    private static String lastNonEmptyLine(String output) {
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty()) return line;
        }
        return "{}";
    }
}
