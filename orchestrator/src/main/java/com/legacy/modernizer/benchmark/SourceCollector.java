package com.legacy.modernizer.benchmark;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks a Java source tree and concatenates {@code .java} files into a single
 * string for inclusion in a single-prompt LLM call.
 *
 * <p>Files are sorted lexicographically (deterministic ordering) and appended with
 * a {@code // ===== path/File.java =====} header until the accumulated character
 * count reaches the configured budget.  The caller receives the concatenated
 * sources and the metadata about what was included vs skipped.
 */
public class SourceCollector {

    private static final Logger log = LoggerFactory.getLogger(SourceCollector.class);

    private final int maxChars;

    /**
     * @param maxChars  maximum total characters to collect; controls context window usage
     *                  (rough rule: 1 token ≈ 4 chars; 128k token window ≈ 512k chars)
     */
    public SourceCollector(int maxChars) {
        this.maxChars = maxChars;
    }

    // -------------------------------------------------------------------------

    public record CollectedSources(
            String  concatenated,
            int     filesIncluded,
            int     filesSkipped,
            int     charsSent,
            List<String> includedPaths
    ) {}

    // -------------------------------------------------------------------------

    /**
     * Collects {@code .java} files from {@code srcRoot} up to {@link #maxChars}.
     *
     * @param srcRoot  root directory to walk (non-existent roots return an empty result)
     */
    public CollectedSources collect(Path srcRoot) {
        if (!Files.isDirectory(srcRoot)) {
            log.warn("[source-collector] srcRoot does not exist: {}", srcRoot);
            return new CollectedSources("", 0, 0, 0, List.of());
        }

        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .sorted(Comparator.comparing(Path::toString))
                .forEach(javaFiles::add);
        } catch (IOException e) {
            log.error("[source-collector] Walk failed on {}: {}", srcRoot, e.getMessage());
            return new CollectedSources("", 0, 0, 0, List.of());
        }

        StringBuilder sb        = new StringBuilder();
        List<String>  included  = new ArrayList<>();
        int           skipped   = 0;

        for (Path file : javaFiles) {
            String header  = "// ===== " + srcRoot.relativize(file) + " =====\n";
            String content;
            try {
                content = Files.readString(file);
            } catch (IOException e) {
                log.warn("[source-collector] Cannot read {}: {}", file, e.getMessage());
                skipped++;
                continue;
            }

            String chunk = header + content + "\n\n";
            if (sb.length() + chunk.length() > maxChars) {
                skipped++;
                log.debug("[source-collector] Budget reached — skipping {}", file.getFileName());
                // Don't break: smaller files later in the list might still fit
                if (sb.length() >= maxChars) break;
                continue;
            }
            sb.append(chunk);
            included.add(srcRoot.relativize(file).toString());
        }

        log.info("[source-collector] {} included, {} skipped, {} chars (budget={})",
                included.size(), skipped, sb.length(), maxChars);

        return new CollectedSources(sb.toString(), included.size(), skipped,
                sb.length(), included);   // charsSent = sb.length()
    }
}
