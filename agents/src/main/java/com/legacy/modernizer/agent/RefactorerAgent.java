package com.legacy.modernizer.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

/**
 * Refactorer agent: transforms a single legacy Java class into a Spring Boot 3
 * microservice component aligned with its DDD bounded context.
 *
 * <p>Flow:
 * <ol>
 *   <li>Creates an {@link AgentTask} (PENDING) and stores the original source as
 *       an {@link Artifact}(ORIGINAL).</li>
 *   <li>Calls the LLM with a transformation prompt.</li>
 *   <li>Stores the transformed code as {@link Artifact}(TRANSFORMED).</li>
 *   <li>Marks the {@link AgentTask} COMPLETED with token usage, or FAILED on error.</li>
 * </ol>
 */
@Component
public class RefactorerAgent {

    private static final Logger log = LoggerFactory.getLogger(RefactorerAgent.class);

    private static final String SYSTEM_PROMPT = """
            You are a Java modernisation expert specialised in migrating legacy Spring MVC \
            monoliths to production-quality Spring Boot 3 microservices.

            Apply ONLY these targeted transformations — preserve all business logic exactly:
            1. Replace javax.* imports with their jakarta.* equivalents
            2. Replace field-injection @Autowired with constructor injection
            3. Replace @Controller with @RestController for JSON REST endpoints; \
               keep @Controller only if the method returns a view name
            4. Remove @ResponseBody annotations made redundant by @RestController
            5. Add @Tag(name = "<serviceName>") and @Operation(summary = "...") annotations \
               from io.swagger.v3.oas.annotations for public controller methods
            6. Update Spring Boot 3-incompatible annotations if present \
               (e.g., @EnableWebMvc → implicit in @SpringBootApplication)

            Output ONLY the complete transformed Java source file — no markdown, no explanation.
            """;

    private final ChatLanguageModel chatModel;
    private final AgentTaskRepository taskRepository;
    private final ArtifactRepository artifactRepository;

    @Value("${anthropic.model:ollama}")
    private String modelName;

    public RefactorerAgent(ChatLanguageModel chatModel,
                           AgentTaskRepository taskRepository,
                           ArtifactRepository artifactRepository) {
        this.chatModel          = chatModel;
        this.taskRepository     = taskRepository;
        this.artifactRepository = artifactRepository;
    }

    /**
     * Transforms a single Java class for the target microservice bounded context.
     *
     * @param jobId         the migration job this class belongs to
     * @param classFqn      fully-qualified class name (e.g. {@code com.example.OwnerController})
     * @param sourceCode    original Java source text
     * @param serviceName   target microservice name (kebab-case, e.g. {@code owner-service})
     * @param domainContext DDD bounded-context description (2-3 sentences from ArchitectAgent)
     * @param filePath      original path relative to the source root (nullable)
     * @return tracking record with the persisted {@link AgentTask} and both {@link Artifact}s
     */
    public RefactorResult refactor(Long jobId,
                                   String classFqn,
                                   String sourceCode,
                                   String serviceName,
                                   String domainContext,
                                   String filePath) {
        log.info("[refactorer] Starting refactor for {} (job {})", classFqn, jobId);

        // 1. Create AgentTask (PENDING)
        AgentTask task = taskRepository.save(AgentTask.builder()
                .jobId(jobId)
                .taskType("TRANSFORM")
                .status(AgentTaskStatus.PENDING)
                .classFqn(classFqn)
                .inputData(Map.of(
                        "serviceName", serviceName,
                        "filePath",    filePath != null ? filePath : ""))
                .build());

        // 2. Persist original source
        Artifact original = artifactRepository.save(Artifact.builder()
                .jobId(jobId)
                .taskId(task.getId())
                .artifactType(ArtifactType.ORIGINAL)
                .classFqn(classFqn)
                .filePath(filePath)
                .content(sourceCode)
                .checksum(sha256(sourceCode))
                .build());

        // 3. Advance to IN_PROGRESS
        task.setStatus(AgentTaskStatus.IN_PROGRESS);
        task = taskRepository.save(task);

        try {
            // 4. Call LLM
            String userPrompt = buildUserPrompt(classFqn, sourceCode, serviceName, domainContext);
            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt))
            );
            String transformedCode = stripCodeFence(response.content().text());

            TokenUsage usage = response.tokenUsage();
            int tokens = (usage != null) ? usage.totalTokenCount() : 0;
            log.debug("[refactorer] LLM returned {} chars, {} tokens for {}", transformedCode.length(), tokens, classFqn);

            // 5. Persist transformed artifact
            Artifact transformed = artifactRepository.save(Artifact.builder()
                    .jobId(jobId)
                    .taskId(task.getId())
                    .artifactType(ArtifactType.TRANSFORMED)
                    .classFqn(classFqn)
                    .filePath(filePath)
                    .content(transformedCode)
                    .checksum(sha256(transformedCode))
                    .build());

            // 6. Mark task COMPLETED
            task.setStatus(AgentTaskStatus.COMPLETED);
            task.setModelUsed(modelName);
            task.setTokensUsed(tokens > 0 ? tokens : null);
            task.setOutputData(Map.of("transformedArtifactId", transformed.getId()));
            task = taskRepository.save(task);

            log.info("[refactorer] Completed {} (task {}, {} tokens)", classFqn, task.getId(), tokens);
            return new RefactorResult(task, original, transformed);

        } catch (Exception e) {
            log.error("[refactorer] Failed to refactor {}: {}", classFqn, e.getMessage(), e);
            task.setStatus(AgentTaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
            throw new RuntimeException("Refactoring failed for " + classFqn + ": " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private String buildUserPrompt(String classFqn, String sourceCode,
                                   String serviceName, String domainContext) {
        return """
                Target microservice: %s
                Bounded context: %s
                Class to transform: %s

                ```java
                %s
                ```
                """.formatted(serviceName, domainContext, classFqn, sourceCode);
    }

    private String stripCodeFence(String raw) {
        return raw.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();
    }

    private String sha256(String content) {
        if (content == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
