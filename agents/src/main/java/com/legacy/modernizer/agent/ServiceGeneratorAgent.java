package com.legacy.modernizer.agent;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.ServiceBoundary;
import com.legacy.modernizer.repository.AgentTaskRepository;
import com.legacy.modernizer.repository.ArtifactRepository;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Phase 3.1 — Service Generator Agent.
 *
 * <p>Given a {@link ServiceBoundary} (from the ArchitectAgent) and optional RAG context
 * snippets retrieved from the original codebase, generates a complete Spring Boot 3
 * microservice: pom.xml, Application, entity, DTO, repository, service, controller.
 *
 * <p>Flow:
 * <ol>
 *   <li>Creates an {@link AgentTask} (task_type = SERVICE_GEN, PENDING → IN_PROGRESS).</li>
 *   <li>Calls the LLM with a structured prompt that demands {@code <file>} XML blocks.</li>
 *   <li>Parses every {@code <file><path>…</path><content>…</content></file>} block.</li>
 *   <li>Persists each parsed file as an {@link Artifact}(SERVICE_CODE).</li>
 *   <li>Marks the task COMPLETED (or FAILED on error).</li>
 * </ol>
 */
@Component
public class ServiceGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(ServiceGeneratorAgent.class);

    // Matches:  <file>\n<path>…</path>\n<content>…[</content>]\n</file>
    // </content> is optional — small models consistently omit it
    private static final Pattern FILE_BLOCK = Pattern.compile(
            "<file>\\s*<path>(.*?)</path>\\s*<content>(.*?)(?:</content>\\s*)?</file>",
            Pattern.DOTALL
    );

    // Fallback: ```java … ``` markdown blocks (small models ignore XML format)
    private static final Pattern MARKDOWN_JAVA_BLOCK = Pattern.compile(
            "```(?:java|xml)?\\s*\\n(.*?)```",
            Pattern.DOTALL
    );
    // Extract package + class name to derive a file path
    private static final Pattern PACKAGE_DECL   = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_DECL     = Pattern.compile(
            "(?:public\\s+)?(?:class|interface|enum|record)\\s+(\\w+)", Pattern.MULTILINE);

    // -------------------------------------------------------------------------
    // System prompts
    // -------------------------------------------------------------------------

    private static final String SYSTEM_PROMPT = """
            You are a Java architect generating production-ready Spring Boot 3 microservices \
            from DDD service boundary analysis.

            OUTPUT FORMAT — produce ONLY the XML file blocks below. \
            No markdown. No prose. No explanation.

            <file>
            <path>pom.xml</path>
            <content>
            ... complete pom.xml content ...
            </content>
            </file>

            <file>
            <path>src/main/java/com/modernized/{pkg}/Application.java</path>
            <content>
            ... complete Java source ...
            </content>
            </file>

            ... (one <file> block per generated file)

            MANDATORY RULES — violation causes compilation failure:
            1.  Java 21, Spring Boot 3.2.5; use jakarta.* (NOT javax.*) throughout.
            2.  groupId = com.modernized; artifactId = {service-name}; version = 1.0.0-SNAPSHOT. \
                These three values are EXACT — do not invent alternatives.
            3.  Package root: com.modernized.{pkg}  (all kebab/hyphens removed, lowercase).
            4.  pom.xml MUST include: spring-boot-starter-web, spring-boot-starter-data-jpa, \
                postgresql (runtime scope), lombok (optional scope).
            5.  Entity classes: annotations in order — @Data @Builder @NoArgsConstructor \
                @AllArgsConstructor @Entity @Table(name="…"). \
                Primary key: private Long id; with @Id @GeneratedValue(strategy=GenerationType.IDENTITY).
            6.  Repository: public interface {Entity}Repository extends JpaRepository<{Entity}, Long> {} \
                — one interface per entity, no implementation.
            7.  Service: @Service @Transactional; all fields final; constructor injection \
                (no @Autowired); standard CRUD — findAll, findById, create, update, delete.
            8.  Controller: @RestController @RequestMapping("/api/{resource}"); use ResponseEntity<>; \
                endpoints: GET /, GET /{id}, POST / (body = entity DTO), \
                PUT /{id} (body = entity DTO), DELETE /{id}.
            9.  Application.java: @SpringBootApplication in root package com.modernized.{pkg}; \
                public static void main(String[] args) { SpringApplication.run(…); }.
            10. ALL imports must be explicit — zero wildcard imports.
            11. Do NOT include spring.datasource properties in Application.java. \
                The service is configured externally.
            12. If this service extends or uses classes from a shared commons module, add EXACTLY \
                this dependency (do NOT invent a different groupId or version): \
                <dependency><groupId>com.modernized</groupId> \
                <artifactId>spring-petclinic-commons</artifactId> \
                <version>1.0.0-SNAPSHOT</version></dependency>
            """;

    /** DD2 — system prompt for generating the shared commons library module. */
    private static final String COMMONS_SYSTEM_PROMPT = """
            You are a Java architect generating a shared utility library module \
            for a modernised legacy application.

            OUTPUT FORMAT — produce ONLY the XML file blocks below. \
            No markdown. No prose. No explanation.

            <file>
            <path>pom.xml</path>
            <content>
            ... complete pom.xml content ...
            </content>
            </file>

            <file>
            <path>src/main/java/com/modernized/{pkg}/{ClassName}.java</path>
            <content>
            ... complete Java source ...
            </content>
            </file>

            ... (one <file> block per class)

            MANDATORY RULES — violation causes compilation failure:
            1.  Java 21; use jakarta.* (NOT javax.*) throughout.
            2.  groupId = com.modernized; artifactId = {service-name}; version = 1.0.0-SNAPSHOT; packaging = jar. \
                These four values are EXACT — do not invent alternatives.
            3.  Package root: com.modernized.{pkg}  (all kebab/hyphens removed, lowercase).
            4.  pom.xml: plain Java library — NO spring-boot-starter-parent, NO spring-boot-maven-plugin. \
                Use <groupId>com.modernized</groupId> as this module's groupId directly (no parent element). \
                Include only: lombok (optional scope), jakarta.persistence-api (provided scope). \
                Set <java.version>21</java.version>.
            5.  Generate one Java class per shared class listed. Each class should be a \
                self-contained utility, base entity, or constant class with reasonable \
                implementations (no application logic assumed).
            6.  Base entity classes: annotate with @MappedSuperclass @Data @NoArgsConstructor. \
                Include an @Id Long id field.
            7.  Utility classes: public final class with private constructor and static methods.
            8.  ALL imports must be explicit — zero wildcard imports.
            9.  NO Application.java — this is a library, not a runnable service.
            """;

    // -------------------------------------------------------------------------

    private final ChatLanguageModel        chatModel;
    private final AgentTaskRepository      taskRepository;
    private final ArtifactRepository       artifactRepository;
    private final CompilationRepairService repairService;

    @Value("${anthropic.model:ollama}")
    private String modelName;

    @Value("${repair.max-attempts:3}")
    private int maxRepairAttempts;

    @Value("${service-gen.max-method-sigs:30}")
    private int maxMethodSigs;

    public ServiceGeneratorAgent(ChatLanguageModel        chatModel,
                                 AgentTaskRepository      taskRepository,
                                 ArtifactRepository       artifactRepository,
                                 CompilationRepairService repairService) {
        this.chatModel         = chatModel;
        this.taskRepository    = taskRepository;
        this.artifactRepository = artifactRepository;
        this.repairService     = repairService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates a complete Spring Boot 3 microservice for the given boundary.
     *
     * @param jobId           the migration job
     * @param boundary        DDD service boundary from the ArchitectAgent
     * @param contextSnippets RAG-retrieved code snippets from the original codebase
     *                        (pass empty list if Qdrant is unavailable)
     * @return tracking record with the persisted task, artifact list, and in-memory files
     */
    public ServiceGenerationResult generate(Long jobId,
                                            ServiceBoundary boundary,
                                            List<String> contextSnippets) {
        return generate(jobId, boundary, contextSnippets, null);
    }

    public ServiceGenerationResult generate(Long jobId,
                                            ServiceBoundary boundary,
                                            List<String> contextSnippets,
                                            Path srcDir) {
        String serviceName = boundary.getServiceName();
        String pkg         = toPackageName(serviceName);
        log.info("[service-gen] Generating {} (pkg={}) for job {}", serviceName, pkg, jobId);

        // 1. Create AgentTask (PENDING)
        AgentTask task = taskRepository.save(AgentTask.builder()
                .jobId(jobId)
                .taskType("SERVICE_GEN")
                .status(AgentTaskStatus.PENDING)
                .classFqn(serviceName)
                .inputData(Map.of(
                        "serviceName",     serviceName,
                        "boundaryId",      boundary.getId() != null ? boundary.getId().toString() : "",
                        "classFqnCount",   String.valueOf(boundary.getClassFqns().size())
                ))
                .build());

        // 2. Advance to IN_PROGRESS
        task.setStatus(AgentTaskStatus.IN_PROGRESS);
        task = taskRepository.save(task);

        try {
            // 3. Call LLM — use commons-specific prompt for -commons boundaries (DD2)
            boolean isCommons = isCommonsBoundary(serviceName);
            String systemPrompt = isCommons ? COMMONS_SYSTEM_PROMPT : SYSTEM_PROMPT;
            List<String> methodSignatures = (srcDir != null && !isCommons)
                    ? extractMethodSignatures(srcDir, boundary.getClassFqns())
                    : List.of();
            if (methodSignatures.size() > maxMethodSigs) {
                methodSignatures = methodSignatures.subList(0, maxMethodSigs);
            }
            if (!methodSignatures.isEmpty()) {
                log.info("[service-gen] Using {} method signatures for {} (cap={})",
                        methodSignatures.size(), serviceName, maxMethodSigs);
            }
            String userPrompt   = isCommons
                    ? buildCommonsUserPrompt(boundary, pkg)
                    : buildUserPrompt(boundary, pkg, contextSnippets, methodSignatures);
            log.debug("[service-gen] {} prompt length: {} chars",
                    isCommons ? "commons" : "service", userPrompt.length());

            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt))
            );
            String raw    = response.content().text();
            TokenUsage tu = response.tokenUsage();
            int tokens    = (tu != null) ? tu.totalTokenCount() : 0;
            log.info("[service-gen] LLM response: {} chars, {} tokens", raw.length(), tokens);
            log.debug("[service-gen][{}] RAW RESPONSE LAST 300 CHARS:\n{}", serviceName,
                    raw.length() > 300 ? raw.substring(raw.length() - 300) : raw);

            // 4. Parse <file> blocks
            List<GeneratedFile> files = parseFileBlocks(raw);
            log.info("[service-gen] Parsed {} files for {}", files.size(), serviceName);

            if (files.isEmpty()) {
                throw new IllegalStateException(
                        "LLM produced no <file> blocks — raw response length: " + raw.length());
            }

            // 5. Persist each file as an Artifact
            List<Artifact> artifacts = new ArrayList<>();
            for (GeneratedFile f : files) {
                Artifact a = artifactRepository.save(Artifact.builder()
                        .jobId(jobId)
                        .taskId(task.getId())
                        .artifactType(ArtifactType.SERVICE_CODE)
                        .classFqn(serviceName)
                        .filePath(f.filePath())
                        .content(f.content())
                        .build());
                artifacts.add(a);
                log.debug("[service-gen] Saved artifact: {}", f.filePath());
            }

            // 6. Compile + repair loop (tracks first_attempt_compiled and repair_attempts on task)
            CompilationRepairService.CompilationRepairResult repair =
                    repairService.compileWithRepair(task, artifacts, maxRepairAttempts);
            log.info("[service-gen] {} compile result: firstAttempt={} final={} attempts={}",
                    serviceName, repair.firstAttemptSuccess(), repair.success(), repair.totalAttempts());

            // Re-read artifacts in case repair updated content
            artifacts = artifactRepository.findByJobIdAndClassFqn(jobId, serviceName);

            // 7. Mark task COMPLETED
            task.setStatus(AgentTaskStatus.COMPLETED);
            task.setModelUsed(modelName);
            task.setTokensUsed(tokens > 0 ? tokens : null);
            task.setOutputData(Map.of(
                    "filesGenerated",        String.valueOf(files.size()),
                    "serviceName",           serviceName,
                    "firstAttemptCompiled",  String.valueOf(repair.firstAttemptSuccess()),
                    "finalCompiled",         String.valueOf(repair.success()),
                    "repairAttempts",        String.valueOf(repair.totalAttempts() - 1)
            ));
            task = taskRepository.save(task);

            log.info("[service-gen] Done — {} files, {} tokens (task {})",
                    files.size(), tokens, task.getId());
            return new ServiceGenerationResult(task, artifacts, files);

        } catch (Exception e) {
            log.error("[service-gen] Failed for {}: {}", serviceName, e.getMessage(), e);
            task.setStatus(AgentTaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
            throw new RuntimeException("Service generation failed for " + serviceName
                    + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Prompt construction
    // -------------------------------------------------------------------------

    String buildUserPrompt(ServiceBoundary boundary, String pkg, List<String> contextSnippets) {
        return buildUserPrompt(boundary, pkg, contextSnippets, List.of());
    }

    String buildUserPrompt(ServiceBoundary boundary, String pkg,
                           List<String> contextSnippets, List<String> methodSignatures) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a Spring Boot 3 microservice.\n\n");

        sb.append("Service name  : ").append(boundary.getServiceName()).append("\n");
        sb.append("Package       : com.modernized.").append(pkg).append("\n");
        sb.append("Domain context: ").append(nvl(boundary.getDescription())).append("\n");
        if (boundary.getRationale() != null) {
            sb.append("Rationale     : ").append(boundary.getRationale()).append("\n");
        }
        sb.append("\n");

        sb.append("Original classes in this service:\n");
        for (String fqn : boundary.getClassFqns()) {
            sb.append("  • ").append(fqn).append("\n");
        }
        sb.append("\n");

        if (!methodSignatures.isEmpty()) {
            sb.append("REQUIRED public methods — you MUST implement ALL of these with the EXACT method names:\n");
            for (String sig : methodSignatures) {
                sb.append("  • ").append(sig).append("\n");
            }
            sb.append("\n");
        }

        if (!contextSnippets.isEmpty()) {
            sb.append("Reference code retrieved from the original codebase:\n\n");
            for (String snippet : contextSnippets) {
                sb.append("```java\n").append(snippet.strip()).append("\n```\n\n");
            }
        }

        sb.append("Generate ALL of the following files inside <file> blocks:\n");
        sb.append("  1. pom.xml\n");
        sb.append("  2. src/main/java/com/modernized/").append(pkg).append("/Application.java\n");
        sb.append("  3. src/main/java/com/modernized/").append(pkg)
                .append("/entity/{PrimaryEntity}.java\n");
        sb.append("  4. src/main/java/com/modernized/").append(pkg)
                .append("/dto/{PrimaryEntity}Request.java\n");
        sb.append("  5. src/main/java/com/modernized/").append(pkg)
                .append("/repository/{PrimaryEntity}Repository.java\n");
        sb.append("  6. src/main/java/com/modernized/").append(pkg)
                .append("/service/{PrimaryEntity}Service.java\n");
        sb.append("  7. src/main/java/com/modernized/").append(pkg)
                .append("/controller/{PrimaryEntity}Controller.java\n");

        return sb.toString();
    }

    /** DD2 — true when this boundary is the auto-generated shared commons module. */
    static boolean isCommonsBoundary(String serviceName) {
        return serviceName != null && serviceName.endsWith("-commons");
    }

    String buildCommonsUserPrompt(ServiceBoundary boundary, String pkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate a shared Java utility library module.\n\n");
        sb.append("Module name : ").append(boundary.getServiceName()).append("\n");
        sb.append("Package     : com.modernized.").append(pkg).append("\n");
        sb.append("Description : ").append(nvl(boundary.getDescription())).append("\n\n");
        sb.append("Shared classes to implement (one <file> block per class):\n");
        if (boundary.getClassFqns() != null) {
            for (String fqn : boundary.getClassFqns()) {
                String simpleName = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                sb.append("  • ").append(simpleName).append("  (original FQN: ").append(fqn).append(")\n");
            }
        }
        sb.append("\nAlso generate the pom.xml for this plain Java library.\n");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // File block parsing
    // -------------------------------------------------------------------------

    List<GeneratedFile> parseFileBlocks(String raw) {
        List<GeneratedFile> files = new ArrayList<>();
        Matcher m = FILE_BLOCK.matcher(raw);
        while (m.find()) {
            String path    = m.group(1).strip();
            String content = m.group(2).strip();
            if (!path.isEmpty() && !content.isEmpty()) {
                files.add(new GeneratedFile(path, content));
            }
        }
        if (!files.isEmpty()) return files;

        // Fallback: model used markdown ```java blocks instead of <file> XML
        log.debug("[service-gen] No <file> blocks found — trying markdown fallback");
        Matcher md = MARKDOWN_JAVA_BLOCK.matcher(raw);
        int idx = 0;
        while (md.find()) {
            String content = md.group(1).strip();
            if (content.isEmpty()) continue;
            String path = inferPathFromContent(content, idx++);
            files.add(new GeneratedFile(path, content));
        }
        if (!files.isEmpty()) log.info("[service-gen] Markdown fallback extracted {} block(s)", files.size());
        return files;
    }

    private String inferPathFromContent(String content, int idx) {
        // Try to derive path from package + class name
        Matcher pkg = PACKAGE_DECL.matcher(content);
        Matcher cls = CLASS_DECL.matcher(content);
        if (pkg.find() && cls.find()) {
            String pkgPath = pkg.group(1).replace('.', '/');
            String className = cls.group(1);
            // pom.xml heuristic: no package declaration but contains <project>
            return "src/main/java/" + pkgPath + "/" + className + ".java";
        }
        if (content.contains("<project") || content.contains("<dependency")) {
            return "pom.xml";
        }
        return "src/main/java/Unknown_" + idx + ".java";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Walks srcDir to find source files for the given class FQNs and extracts
     * their public method signatures (returnType methodName(paramType paramName, ...)).
     */
    List<String> extractMethodSignatures(Path srcDir, List<String> classFqns) {
        if (srcDir == null || !Files.isDirectory(srcDir) || classFqns == null || classFqns.isEmpty()) {
            return List.of();
        }
        Set<String> targetFqns = new HashSet<>(classFqns);
        List<String> signatures = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(srcDir)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .forEach(file -> {
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(file);
                        String pkg = cu.getPackageDeclaration()
                                .map(pd -> pd.getNameAsString()).orElse("");
                        // Check if this file belongs to any target class
                        boolean matched = cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                                .stream()
                                .anyMatch(c -> targetFqns.contains(
                                        pkg.isEmpty() ? c.getNameAsString() : pkg + "." + c.getNameAsString()));
                        if (!matched) return;
                        cu.findAll(MethodDeclaration.class).stream()
                                .filter(m -> m.isPublic())
                                .forEach(m -> {
                                    String params = m.getParameters().stream()
                                            .map(p -> p.getType().asString() + " " + p.getNameAsString())
                                            .collect(Collectors.joining(", "));
                                    signatures.add(m.getType().asString()
                                            + " " + m.getNameAsString()
                                            + "(" + params + ")");
                                });
                    } catch (ParseProblemException | IOException ignored) {}
                });
        } catch (IOException e) {
            log.warn("[service-gen] Could not walk srcDir for method extraction: {}", e.getMessage());
        }
        return signatures;
    }

    /** "owner-service" → "ownerservice" */
    static String toPackageName(String serviceName) {
        return serviceName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
