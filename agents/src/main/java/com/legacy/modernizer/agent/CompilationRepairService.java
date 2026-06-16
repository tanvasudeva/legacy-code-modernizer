package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.repository.AgentTaskRepository;
import com.legacy.modernizer.repository.ArtifactRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Phase 4.7 — Compilation repair loop.
 *
 * <p>After {@link ServiceGeneratorAgent} persists generated artifacts, this service:
 * <ol>
 *   <li>Compiles the service in a temp directory via {@code mvn compile}.</li>
 *   <li>Records {@code first_attempt_compiled} on the {@link AgentTask}.</li>
 *   <li>On failure, parses structured {@link CompilerError}s from Maven stderr.</li>
 *   <li>Calls the LLM with the original code + specific errors and asks it to fix only those errors.</li>
 *   <li>Updates {@link Artifact} content in the DB and retries compilation.</li>
 *   <li>Repeats up to {@code repair.max-attempts} total attempts (default 3).</li>
 * </ol>
 *
 * <p>Result is a {@link CompilationRepairResult} that the caller can use to update
 * task output_data and report improvement metrics.
 */
@Component
public class CompilationRepairService {

    private static final Logger log = LoggerFactory.getLogger(CompilationRepairService.class);

    // Matches: [ERROR] /tmp/.../File.java:[10,5] cannot find symbol
    private static final Pattern MVN_ERROR = Pattern.compile(
            "\\[ERROR]\\s+(.*?\\.java):\\[(\\d+),\\d+]\\s+(.+)");

    // Same <file> format as ServiceGeneratorAgent — </content> is optional for small models
    private static final Pattern FILE_BLOCK = Pattern.compile(
            "<file>\\s*<path>(.*?)</path>\\s*<content>(.*?)(?:</content>\\s*)?</file>",
            Pattern.DOTALL);

    private static final String SB_PARENT = """
                <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.5</version>
                    <relativePath/>
                </parent>
            """;

    /** Injects Spring Boot parent into a pom.xml that the LLM generated without one. */
    static String patchPomParent(String pom) {
        if (pom == null || pom.contains("<parent>")) return pom;
        // fix common model mistake: wrong groupId for jakarta.persistence
        pom = pom.replace(
                "<groupId>jakarta.persistence-api</groupId>",
                "<groupId>jakarta.persistence</groupId>");
        // insert parent block right after </modelVersion>
        return pom.replace("</modelVersion>", "</modelVersion>\n" + SB_PARENT);
    }

    private static final String REPAIR_SYSTEM_PROMPT = """
            You are a Java compiler-error specialist. You will receive Spring Boot 3 source files \
            and a list of exact compiler errors. Your ONLY task is to fix those specific errors.

            Rules:
            1. Do NOT change any business logic, method names, or class structure.
            2. Use jakarta.* (NOT javax.*) for all imports.
            3. Return ONLY the files that require changes, using the SAME <file> format:

            <file>
            <path>src/main/java/com/modernized/.../ClassName.java</path>
            <content>
            ... complete corrected file ...
            </content>
            </file>

            4. Every returned file must be complete — do not use ellipses or omit any methods.
            5. Do NOT modify pom.xml unless a pom error is listed.
            """;

    @Value("${repair.max-attempts:3}")
    private int defaultMaxAttempts;

    private final ChatLanguageModel   chatModel;
    private final ArtifactRepository  artifactRepository;
    private final AgentTaskRepository taskRepository;

    public CompilationRepairService(ChatLanguageModel   chatModel,
                                    ArtifactRepository  artifactRepository,
                                    AgentTaskRepository taskRepository) {
        this.chatModel         = chatModel;
        this.artifactRepository = artifactRepository;
        this.taskRepository    = taskRepository;
    }

    // ─── Public result ────────────────────────────────────────────────────────

    /**
     * Result of the full compile+repair loop for one service.
     *
     * @param success             whether the service compiled successfully in the end
     * @param firstAttemptSuccess whether it compiled on the very first try
     * @param totalAttempts       total compile invocations (1 = first-attempt success)
     * @param lastErrors          compiler errors from the last failed attempt (empty on success)
     */
    public record CompilationRepairResult(
            boolean            success,
            boolean            firstAttemptSuccess,
            int                totalAttempts,
            List<CompilerError> lastErrors
    ) {}

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Runs the compile → repair → retry loop for the given task's artifacts.
     *
     * @param task        the SERVICE_GEN AgentTask (will have repairAttempts /
     *                    firstAttemptCompiled updated in the DB)
     * @param artifacts   all artifacts for this service (pom.xml + Java sources)
     * @param maxAttempts maximum total compile invocations (≥ 1)
     */
    public CompilationRepairResult compileWithRepair(AgentTask task,
                                                     List<Artifact> artifacts,
                                                     int maxAttempts) {
        String serviceName = task.getClassFqn() != null ? task.getClassFqn() : "unknown";
        log.info("[repair] Starting compile+repair for {} (max {} attempts)", serviceName, maxAttempts);

        List<Artifact> working = new ArrayList<>(artifacts);

        // 1. First compile attempt
        CompileAttemptResult first = compileArtifacts(serviceName, working);
        task.setFirstAttemptCompiled(first.success());
        task.setRepairAttempts(0);
        taskRepository.save(task);

        if (first.success()) {
            log.info("[repair] {} compiled on first attempt", serviceName);
            return new CompilationRepairResult(true, true, 1, List.of());
        }

        log.warn("[repair] {} first-attempt compile failed — beginning repair loop", serviceName);

        // 2. Repair loop (attempts 2..maxAttempts)
        CompileAttemptResult latest       = first;
        List<CompilerError>  lastErrors   = List.of();

        for (int attempt = 2; attempt <= maxAttempts; attempt++) {
            List<CompilerError> errors = parseErrors(latest.output());
            if (errors.isEmpty()) {
                log.warn("[repair] {} attempt {}: no parseable errors — giving up", serviceName, attempt);
                break;
            }
            lastErrors = errors;
            log.info("[repair] {} attempt {}/{}: fixing {} error(s)", serviceName, attempt, maxAttempts, errors.size());

            // Ask LLM to fix errors
            List<Artifact> patched = repairWithLlm(working, serviceName, errors, attempt);
            if (!patched.isEmpty()) {
                working = mergePatches(working, patched);
                for (Artifact a : patched) {
                    artifactRepository.save(a);
                }
            }

            // Track iterations in DB
            task.setRepairAttempts(attempt - 1);
            taskRepository.save(task);

            latest = compileArtifacts(serviceName, working);
            if (latest.success()) {
                log.info("[repair] {} fixed after {} repair iteration(s)", serviceName, attempt - 1);
                return new CompilationRepairResult(true, false, attempt, lastErrors);
            }
        }

        log.warn("[repair] {} still failing after {} repair iteration(s)", serviceName, task.getRepairAttempts());
        return new CompilationRepairResult(false, false, maxAttempts, lastErrors);
    }

    /** Convenience overload using the configured default max attempts. */
    public CompilationRepairResult compileWithRepair(AgentTask task, List<Artifact> artifacts) {
        return compileWithRepair(task, artifacts, defaultMaxAttempts);
    }

    // ─── Compilation ──────────────────────────────────────────────────────────

    private record CompileAttemptResult(boolean success, int exitCode, String output) {}

    private CompileAttemptResult compileArtifacts(String serviceName, List<Artifact> artifacts) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("lcm-repair-" + serviceName + "-");
            for (Artifact a : artifacts) {
                if (a.getFilePath() == null || a.getContent() == null) continue;
                Path target = tempDir.resolve(a.getFilePath()).normalize();
                if (!target.startsWith(tempDir))
                    throw new SecurityException("Path traversal: " + a.getFilePath());
                Files.createDirectories(target.getParent());
                String content = "pom.xml".equals(a.getFilePath())
                        ? patchPomParent(a.getContent())
                        : a.getContent();
                Files.writeString(target, content);
            }

            if (!Files.exists(tempDir.resolve("pom.xml"))) {
                log.warn("[repair] No pom.xml for {} — cannot compile", serviceName);
                return new CompileAttemptResult(false, -1, "No pom.xml");
            }

            String mvn = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "mvn.cmd" : "mvn";

            ProcessBuilder pb = new ProcessBuilder(mvn, "compile", "--no-transfer-progress")
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true);

            Process proc   = pb.start();
            String  output = new String(proc.getInputStream().readAllBytes());
            int     exit   = proc.waitFor();

            return new CompileAttemptResult(exit == 0, exit, output);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CompileAttemptResult(false, -99, "Interrupted");
        } catch (Exception e) {
            log.warn("[repair] Compile exception for {}: {}", serviceName, e.getMessage());
            return new CompileAttemptResult(false, -1, e.getMessage() != null ? e.getMessage() : "");
        } finally {
            deleteTree(tempDir);
        }
    }

    // ─── Error parsing ────────────────────────────────────────────────────────

    /**
     * Parses structured {@link CompilerError}s from Maven compile output.
     * Handles the standard javac-through-Maven format:
     * {@code [ERROR] /tmp/.../File.java:[10,5] cannot find symbol}
     */
    static List<CompilerError> parseErrors(String output) {
        List<CompilerError> errors = new ArrayList<>();
        if (output == null) return errors;
        for (String line : output.split("\n")) {
            Matcher m = MVN_ERROR.matcher(line.trim());
            if (m.find()) {
                String file    = Path.of(m.group(1)).getFileName().toString();
                int    lineNum = Integer.parseInt(m.group(2));
                String msg     = m.group(3).strip();
                errors.add(new CompilerError(file, lineNum, msg));
            }
        }
        // Deduplicate; preserve order
        return errors.stream().distinct().toList();
    }

    // ─── LLM repair ──────────────────────────────────────────────────────────

    private List<Artifact> repairWithLlm(List<Artifact> artifacts,
                                         String         serviceName,
                                         List<CompilerError> errors,
                                         int            attempt) {
        String userPrompt = buildRepairPrompt(artifacts, serviceName, errors, attempt);
        try {
            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(REPAIR_SYSTEM_PROMPT),
                            UserMessage.from(userPrompt)));
            String raw = response.content().text();
            log.debug("[repair] LLM repair response for {}: {} chars", serviceName, raw.length());
            return parseRepairResponse(raw, artifacts);
        } catch (Exception e) {
            log.error("[repair] LLM call failed for {} attempt {}: {}", serviceName, attempt, e.getMessage());
            return List.of();
        }
    }

    private static String buildRepairPrompt(List<Artifact>      artifacts,
                                            String              serviceName,
                                            List<CompilerError> errors,
                                            int                 attempt) {
        StringBuilder sb = new StringBuilder();
        sb.append("Service: ").append(serviceName)
          .append(" (repair attempt ").append(attempt).append(")\n\n");

        sb.append("=== COMPILER ERRORS TO FIX ===\n");
        for (CompilerError e : errors) {
            sb.append("  ").append(e.file())
              .append(" line ").append(e.line())
              .append(": ").append(e.message()).append("\n");
        }

        sb.append("\n=== CURRENT SOURCE FILES ===\n");
        for (Artifact a : artifacts) {
            if (a.getFilePath() == null || a.getContent() == null) continue;
            if (a.getFilePath().endsWith("pom.xml")) continue; // skip pom unless error in pom
            sb.append("<file>\n<path>").append(a.getFilePath()).append("</path>\n<content>\n")
              .append(a.getContent()).append("\n</content>\n</file>\n\n");
        }

        sb.append("Fix ONLY the listed compiler errors. Return ONLY the files that need changes.");
        return sb.toString();
    }

    private static List<Artifact> parseRepairResponse(String raw, List<Artifact> originals) {
        Map<String, Artifact> byPath = originals.stream()
                .filter(a -> a.getFilePath() != null)
                .collect(Collectors.toMap(Artifact::getFilePath, a -> a, (a, b) -> a));

        List<Artifact> patched = new ArrayList<>();
        Matcher m = FILE_BLOCK.matcher(raw);
        while (m.find()) {
            String path    = m.group(1).strip();
            String content = m.group(2).strip();
            Artifact orig  = byPath.get(path);
            if (orig != null && !content.isEmpty()) {
                orig.setContent(content);
                patched.add(orig);
            }
        }
        return patched;
    }

    /** Applies patches into the working list (same instances — already mutated). */
    private static List<Artifact> mergePatches(List<Artifact> working, List<Artifact> patched) {
        return working; // patched items are same object references — content already updated
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder())
             .map(Path::toFile)
             .forEach(java.io.File::delete);
        } catch (IOException e) {
            log.warn("[repair] Could not delete temp dir {}: {}", dir, e.getMessage());
        }
    }
}
