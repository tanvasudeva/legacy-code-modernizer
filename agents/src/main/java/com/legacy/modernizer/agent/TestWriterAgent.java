package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.repository.AgentTaskRepository;
import com.legacy.modernizer.repository.ArtifactRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 3.2 — Test Writer Agent.
 *
 * <p>Given the source files of a generated Spring Boot microservice, produces:
 * <ul>
 *   <li>{@code {Entity}ServiceTest.java} — Mockito unit tests for the service layer</li>
 *   <li>{@code {Entity}ControllerTest.java} — {@code @WebMvcTest} MockMvc tests for the controller</li>
 *   <li>An updated {@code pom.xml} with {@code spring-boot-starter-test} and {@code h2} deps</li>
 * </ul>
 *
 * <p>Each file is persisted as {@link ArtifactType#TEST_CODE}. The LLM is given the exact
 * source code of the entity, service, and controller so it can write correct assertions.
 *
 * <p>RAG query "existing test utilities and @DataJpaTest patterns" retrieves test idioms
 * from the original codebase to guide test style.
 */
@Component
public class TestWriterAgent {

    private static final Logger log = LoggerFactory.getLogger(TestWriterAgent.class);

    private static final Pattern FILE_BLOCK = Pattern.compile(
            "<file>\\s*<path>(.*?)</path>\\s*<content>(.*?)</content>\\s*</file>",
            Pattern.DOTALL
    );

    // -------------------------------------------------------------------------
    // System prompt
    // -------------------------------------------------------------------------

    private static final String SYSTEM_PROMPT = """
            You are a Java testing expert generating comprehensive JUnit 5 test suites \
            for Spring Boot 3 microservices.

            OUTPUT FORMAT — produce ONLY these XML file blocks, nothing else:

            <file>
            <path>pom.xml</path>
            <content>
            (complete pom.xml WITH test dependencies)
            </content>
            </file>

            <file>
            <path>src/test/java/com/modernized/{pkg}/service/{Entity}ServiceTest.java</path>
            <content>
            (complete test class)
            </content>
            </file>

            <file>
            <path>src/test/java/com/modernized/{pkg}/controller/{Entity}ControllerTest.java</path>
            <content>
            (complete test class)
            </content>
            </file>

            MANDATORY RULES — violation causes compilation or test failure:
            1.  Java 21, JUnit 5 (org.junit.jupiter.api.*) — NEVER import JUnit 4.
            2.  All assertions use AssertJ: assertThat(actual)… — never assertEquals.
            3.  pom.xml: copy the provided pom exactly, then ADD inside <dependencies>:
                  <dependency>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-test</artifactId>
                    <scope>test</scope>
                  </dependency>
                  <dependency>
                    <groupId>com.h2database</groupId>
                    <artifactId>h2</artifactId>
                    <scope>test</scope>
                  </dependency>
            4.  Service test (@ExtendWith(MockitoExtension.class)):
                  - @Mock {Entity}Repository; instantiate service in @BeforeEach via constructor
                  - @BeforeEach: build a sample {Entity} with id=1L and realistic field values
                  - @Test findAll: when(repo.findAll()).thenReturn(List.of(sample)); assertThat list size > 0
                  - @Test findById happy: when(repo.findById(1L)).thenReturn(Optional.of(sample))
                  - @Test findById notFound: when(repo.findById(99L)).thenReturn(Optional.empty()); \
                    assertThatThrownBy → NoSuchElementException
                  - @Test create: when(repo.save(any())).thenReturn(sample); verify(repo).save(any())
                  - @Test delete: doNothing().when(repo).deleteById(1L); verify(repo).deleteById(1L)
            5.  Controller test (@WebMvcTest({Entity}Controller.class)):
                  - @MockBean {Entity}Service; @Autowired MockMvc mockMvc; @Autowired ObjectMapper objectMapper
                  - @Test GET / → status 200 + JSON array (use jsonPath("$[0].{firstStringField}"))
                  - @Test GET /{id} → status 200 + jsonPath on entity field
                  - @Test POST / → status 200 (stub service.create(any()) → sample entity)
                  - @Test DELETE /{id} → status 204
            6.  Use static imports for MockMvcRequestBuilders and MockMvcResultMatchers.
            7.  All imports must be explicit and complete — zero wildcard imports.
            8.  Package declarations must match file paths exactly.
            """;

    // -------------------------------------------------------------------------

    private final ChatLanguageModel   chatModel;
    private final AgentTaskRepository taskRepository;
    private final ArtifactRepository  artifactRepository;

    @Value("${anthropic.model:ollama}")
    private String modelName;

    public TestWriterAgent(ChatLanguageModel chatModel,
                           AgentTaskRepository taskRepository,
                           ArtifactRepository artifactRepository) {
        this.chatModel         = chatModel;
        this.taskRepository    = taskRepository;
        this.artifactRepository = artifactRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Generates JUnit 5 tests for a Spring Boot microservice.
     *
     * @param jobId           migration job
     * @param serviceName     kebab-case service name, e.g. {@code owner-service}
     * @param packageName     Java package fragment, e.g. {@code ownerservice}
     * @param entityName      primary entity simple name, e.g. {@code Owner}
     * @param serviceSource   full source of the {@code {Entity}Service.java} file
     * @param controllerSource full source of the {@code {Entity}Controller.java} file
     * @param entitySource    full source of the {@code {Entity}.java} entity file
     * @param pomSource       current {@code pom.xml} content (will be patched with test deps)
     * @param ragSnippets     test idiom snippets retrieved from the original codebase via RAG
     */
    public TestGenerationResult generate(Long jobId,
                                         String serviceName,
                                         String packageName,
                                         String entityName,
                                         String serviceSource,
                                         String controllerSource,
                                         String entitySource,
                                         String pomSource,
                                         List<String> ragSnippets) {
        log.info("[test-writer] Generating tests for {} (entity={}, job={})",
                serviceName, entityName, jobId);

        AgentTask task = taskRepository.save(AgentTask.builder()
                .jobId(jobId)
                .taskType("TEST_GEN")
                .status(AgentTaskStatus.PENDING)
                .classFqn(serviceName)
                .inputData(Map.of("serviceName", serviceName, "entityName", entityName))
                .build());

        task.setStatus(AgentTaskStatus.IN_PROGRESS);
        task = taskRepository.save(task);

        try {
            String userPrompt = buildUserPrompt(serviceName, packageName, entityName,
                    serviceSource, controllerSource, entitySource, pomSource, ragSnippets);

            Response<AiMessage> response = chatModel.generate(
                    List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt)));
            String raw    = response.content().text();
            TokenUsage tu = response.tokenUsage();
            int tokens    = (tu != null) ? tu.totalTokenCount() : 0;
            log.info("[test-writer] LLM response: {} chars, {} tokens", raw.length(), tokens);

            List<GeneratedFile> files = parseFileBlocks(raw);
            log.info("[test-writer] Parsed {} test files for {}", files.size(), serviceName);

            if (files.isEmpty()) {
                throw new IllegalStateException(
                        "LLM produced no <file> blocks — raw length: " + raw.length());
            }

            List<Artifact> artifacts = new ArrayList<>();
            for (GeneratedFile f : files) {
                Artifact a = artifactRepository.save(Artifact.builder()
                        .jobId(jobId)
                        .taskId(task.getId())
                        .artifactType(ArtifactType.TEST_CODE)
                        .classFqn(serviceName)
                        .filePath(f.filePath())
                        .content(f.content())
                        .build());
                artifacts.add(a);
                log.debug("[test-writer] Saved: {}", f.filePath());
            }

            task.setStatus(AgentTaskStatus.COMPLETED);
            task.setModelUsed(modelName);
            task.setTokensUsed(tokens > 0 ? tokens : null);
            task.setOutputData(Map.of("testFilesGenerated", String.valueOf(files.size())));
            task = taskRepository.save(task);

            log.info("[test-writer] Done — {} files, {} tokens (task {})",
                    files.size(), tokens, task.getId());
            return new TestGenerationResult(task, artifacts, files);

        } catch (Exception e) {
            log.error("[test-writer] Failed for {}: {}", serviceName, e.getMessage(), e);
            task.setStatus(AgentTaskStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            taskRepository.save(task);
            throw new RuntimeException(
                    "Test generation failed for " + serviceName + ": " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Prompt
    // -------------------------------------------------------------------------

    String buildUserPrompt(String serviceName, String packageName, String entityName,
                           String serviceSource, String controllerSource,
                           String entitySource, String pomSource,
                           List<String> ragSnippets) {
        StringBuilder sb = new StringBuilder();
        sb.append("Generate JUnit 5 tests for the ").append(serviceName)
                .append(" Spring Boot microservice.\n\n");
        sb.append("Package : com.modernized.").append(packageName).append("\n");
        sb.append("Entity  : ").append(entityName).append("\n\n");

        sb.append("=== Service layer (write Mockito unit tests for this) ===\n")
                .append(serviceSource).append("\n\n");
        sb.append("=== REST controller (write @WebMvcTest tests for this) ===\n")
                .append(controllerSource).append("\n\n");
        sb.append("=== Entity class (use for building test fixtures) ===\n")
                .append(entitySource).append("\n\n");
        sb.append("=== Current pom.xml (MUST be output with test deps added) ===\n")
                .append(pomSource).append("\n\n");

        if (!ragSnippets.isEmpty()) {
            sb.append("=== Test patterns from original codebase (for style reference) ===\n");
            ragSnippets.forEach(s -> sb.append("```java\n").append(s.strip()).append("\n```\n\n"));
        }

        sb.append("Generate these 3 files inside <file> blocks:\n")
                .append("  1. pom.xml  (copy above, add test deps)\n")
                .append("  2. src/test/java/com/modernized/").append(packageName)
                .append("/service/").append(entityName).append("ServiceTest.java\n")
                .append("  3. src/test/java/com/modernized/").append(packageName)
                .append("/controller/").append(entityName).append("ControllerTest.java\n");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Parsing (same regex as ServiceGeneratorAgent)
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
        return files;
    }
}
