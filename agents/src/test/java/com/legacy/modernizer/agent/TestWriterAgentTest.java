package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.repository.AgentTaskRepository;
import com.legacy.modernizer.repository.ArtifactRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TestWriterAgentTest {

    @Mock ChatLanguageModel   chatModel;
    @Mock AgentTaskRepository taskRepository;
    @Mock ArtifactRepository  artifactRepository;

    TestWriterAgent agent;
    AtomicLong idSeq = new AtomicLong(1);

    private static final String SERVICE_SRC = """
            @Service @Transactional
            public class OwnerService {
                private final OwnerRepository repo;
                public OwnerService(OwnerRepository repo) { this.repo = repo; }
                public List<Owner> findAll() { return repo.findAll(); }
            }
            """;
    private static final String CTRL_SRC = """
            @RestController @RequestMapping("/api/owners")
            public class OwnerController {
                @GetMapping public List<Owner> list() { return svc.findAll(); }
            }
            """;
    private static final String ENTITY_SRC = "@Entity public class Owner { @Id Long id; }";
    private static final String POM_SRC =
            "<project><groupId>com.modernized</groupId><artifactId>owner-service</artifactId></project>";

    private static final String MOCK_LLM_RESPONSE = """
            <file>
            <path>pom.xml</path>
            <content>
            <project><groupId>com.modernized</groupId><artifactId>owner-service</artifactId>
            <dependencies><dependency><groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
            </dependencies></project>
            </content>
            </file>
            <file>
            <path>src/test/java/com/modernized/ownerservice/service/OwnerServiceTest.java</path>
            <content>
            package com.modernized.ownerservice.service;
            import org.junit.jupiter.api.extension.ExtendWith;
            import org.mockito.junit.jupiter.MockitoExtension;
            @ExtendWith(MockitoExtension.class)
            class OwnerServiceTest {}
            </content>
            </file>
            <file>
            <path>src/test/java/com/modernized/ownerservice/controller/OwnerControllerTest.java</path>
            <content>
            package com.modernized.ownerservice.controller;
            import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
            @WebMvcTest(Object.class)
            class OwnerControllerTest {}
            </content>
            </file>
            """;

    @BeforeEach
    void setUp() {
        agent = new TestWriterAgent(chatModel, taskRepository, artifactRepository);

        when(taskRepository.save(any())).thenAnswer(inv -> {
            AgentTask t = inv.getArgument(0);
            if (t.getId() == null) t = AgentTask.builder()
                    .id(idSeq.getAndIncrement()).jobId(t.getJobId())
                    .taskType(t.getTaskType()).status(t.getStatus())
                    .classFqn(t.getClassFqn()).inputData(t.getInputData())
                    .outputData(t.getOutputData()).modelUsed(t.getModelUsed())
                    .tokensUsed(t.getTokensUsed()).errorMessage(t.getErrorMessage())
                    .build();
            return t;
        });
        when(artifactRepository.save(any())).thenAnswer(inv -> {
            Artifact a = inv.getArgument(0);
            if (a.getId() == null) a = Artifact.builder()
                    .id(idSeq.getAndIncrement()).jobId(a.getJobId()).taskId(a.getTaskId())
                    .artifactType(a.getArtifactType()).classFqn(a.getClassFqn())
                    .filePath(a.getFilePath()).content(a.getContent())
                    .build();
            return a;
        });
    }

    private void stubLlm(String text) {
        Response<AiMessage> r = Response.from(AiMessage.from(text), new TokenUsage(600, 1800));
        when(chatModel.generate(anyList())).thenReturn(r);
    }

    // -----------------------------------------------------------------
    // File block parsing
    // -----------------------------------------------------------------

    @Test
    void parsesThreeBlocksFromMockResponse() {
        assertThat(agent.parseFileBlocks(MOCK_LLM_RESPONSE)).hasSize(3);
    }

    @Test
    void firstParsedFileIsPom() {
        assertThat(agent.parseFileBlocks(MOCK_LLM_RESPONSE).get(0).filePath())
                .isEqualTo("pom.xml");
    }

    @Test
    void serviceTestPathContainsServiceTest() {
        List<GeneratedFile> files = agent.parseFileBlocks(MOCK_LLM_RESPONSE);
        assertThat(files.get(1).filePath()).contains("ServiceTest.java");
    }

    @Test
    void controllerTestPathContainsControllerTest() {
        List<GeneratedFile> files = agent.parseFileBlocks(MOCK_LLM_RESPONSE);
        assertThat(files.get(2).filePath()).contains("ControllerTest.java");
    }

    // -----------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------

    @Test
    void taskIsCompletedOnSuccess() {
        stubLlm(MOCK_LLM_RESPONSE);
        TestGenerationResult r = agent.generate(1L, "owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of());
        assertThat(r.task().getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
    }

    @Test
    void threeArtifactsAreStored() {
        stubLlm(MOCK_LLM_RESPONSE);
        TestGenerationResult r = agent.generate(1L, "owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of());
        assertThat(r.artifacts()).hasSize(3);
    }

    @Test
    void allArtifactsHaveTestCodeType() {
        stubLlm(MOCK_LLM_RESPONSE);
        TestGenerationResult r = agent.generate(1L, "owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of());
        r.artifacts().forEach(a -> assertThat(a.getArtifactType()).isEqualTo(ArtifactType.TEST_CODE));
    }

    @Test
    void taskTypeIsTestGen() {
        stubLlm(MOCK_LLM_RESPONSE);
        agent.generate(1L, "owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of());
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getTaskType()).isEqualTo("TEST_GEN");
    }

    @Test
    void filePathIsStoredOnArtifact() {
        stubLlm(MOCK_LLM_RESPONSE);
        TestGenerationResult r = agent.generate(1L, "owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of());
        List<String> paths = r.artifacts().stream().map(Artifact::getFilePath).toList();
        assertThat(paths).contains("pom.xml");
    }

    @Test
    void pomContentContainsTestDep() {
        stubLlm(MOCK_LLM_RESPONSE);
        TestGenerationResult r = agent.generate(1L, "owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of());
        String pom = r.files().stream().filter(f -> f.filePath().equals("pom.xml"))
                .findFirst().orElseThrow().content();
        assertThat(pom).contains("spring-boot-starter-test");
    }

    // -----------------------------------------------------------------
    // Error path
    // -----------------------------------------------------------------

    @Test
    void marksTaskFailedWhenLlmThrows() {
        when(chatModel.generate(anyList())).thenThrow(new RuntimeException("API down"));
        assertThatThrownBy(() -> agent.generate(1L, "owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Test generation failed");
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().stream()
                .anyMatch(t -> AgentTaskStatus.FAILED.equals(t.getStatus()))).isTrue();
    }

    @Test
    void marksTaskFailedWhenNoFileBlocks() {
        stubLlm("Here are your tests! (no xml blocks included)");
        assertThatThrownBy(() -> agent.generate(1L, "owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of()))
                .isInstanceOf(RuntimeException.class);
    }

    // -----------------------------------------------------------------
    // User prompt sanity
    // -----------------------------------------------------------------

    @Test
    void promptContainsEntityName() {
        String prompt = agent.buildUserPrompt("owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of());
        assertThat(prompt).contains("Owner").contains("ownerservice");
    }

    @Test
    void promptContainsServiceSource() {
        String prompt = agent.buildUserPrompt("owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of());
        assertThat(prompt).contains("OwnerService");
    }

    @Test
    void promptContainsPomSource() {
        String prompt = agent.buildUserPrompt("owner-service", "ownerservice", "Owner",
                SERVICE_SRC, CTRL_SRC, ENTITY_SRC, POM_SRC, List.of());
        assertThat(prompt).contains("owner-service");
    }
}
