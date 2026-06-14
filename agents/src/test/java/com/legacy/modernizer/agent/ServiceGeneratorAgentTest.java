package com.legacy.modernizer.agent;

import com.legacy.modernizer.model.AgentTask;
import com.legacy.modernizer.model.AgentTaskStatus;
import com.legacy.modernizer.model.Artifact;
import com.legacy.modernizer.model.ArtifactType;
import com.legacy.modernizer.model.ServiceBoundary;
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
class ServiceGeneratorAgentTest {

    @Mock ChatLanguageModel       chatModel;
    @Mock AgentTaskRepository     taskRepository;
    @Mock ArtifactRepository      artifactRepository;
    @Mock CompilationRepairService repairService;

    ServiceGeneratorAgent agent;
    AtomicLong idSeq = new AtomicLong(1);

    private static final ServiceBoundary OWNER_BOUNDARY = ServiceBoundary.builder()
            .id(10L)
            .jobId(1L)
            .serviceName("owner-service")
            .description("Manages owner registration, lookup, and profile updates.")
            .rationale("Owner entity forms a natural bounded context.")
            .classFqns(List.of(
                    "org.petclinic.owner.Owner",
                    "org.petclinic.owner.OwnerController",
                    "org.petclinic.owner.OwnerRepository"
            ))
            .build();

    private static final String MOCK_LLM_RESPONSE = """
            <file>
            <path>pom.xml</path>
            <content>
            <?xml version="1.0"?>
            <project><modelVersion>4.0.0</modelVersion><artifactId>owner-service</artifactId></project>
            </content>
            </file>
            <file>
            <path>src/main/java/com/modernized/ownerservice/Application.java</path>
            <content>
            package com.modernized.ownerservice;
            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            @SpringBootApplication
            public class Application {
                public static void main(String[] args) { SpringApplication.run(Application.class, args); }
            }
            </content>
            </file>
            <file>
            <path>src/main/java/com/modernized/ownerservice/entity/Owner.java</path>
            <content>
            package com.modernized.ownerservice.entity;
            import jakarta.persistence.*;
            import lombok.*;
            @Data @Builder @NoArgsConstructor @AllArgsConstructor
            @Entity @Table(name = "owners")
            public class Owner {
                @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
                private String firstName;
                private String lastName;
            }
            </content>
            </file>
            """;

    @BeforeEach
    void setUp() {
        agent = new ServiceGeneratorAgent(chatModel, taskRepository, artifactRepository, repairService);

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
        Response<AiMessage> r = Response.from(AiMessage.from(text), new TokenUsage(500, 2000));
        when(chatModel.generate(anyList())).thenReturn(r);
    }

    // -----------------------------------------------------------------
    // File block parsing
    // -----------------------------------------------------------------

    @Test
    void parsesThreeFileBlocksFromResponse() {
        List<GeneratedFile> files = agent.parseFileBlocks(MOCK_LLM_RESPONSE);
        assertThat(files).hasSize(3);
    }

    @Test
    void parsedFileHasCorrectPath() {
        List<GeneratedFile> files = agent.parseFileBlocks(MOCK_LLM_RESPONSE);
        assertThat(files.get(0).filePath()).isEqualTo("pom.xml");
    }

    @Test
    void parsedFileHasNonEmptyContent() {
        List<GeneratedFile> files = agent.parseFileBlocks(MOCK_LLM_RESPONSE);
        files.forEach(f -> assertThat(f.content()).isNotBlank());
    }

    @Test
    void emptyResponseProducesNoFiles() {
        assertThat(agent.parseFileBlocks("no file blocks here")).isEmpty();
    }

    // -----------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------

    @Test
    void taskIsMarkedCompletedOnSuccess() {
        stubLlm(MOCK_LLM_RESPONSE);
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, List.of());
        assertThat(result.task().getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
    }

    @Test
    void oneArtifactPerGeneratedFile() {
        stubLlm(MOCK_LLM_RESPONSE);
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, List.of());
        assertThat(result.artifacts()).hasSize(3);
        assertThat(result.files()).hasSize(3);
    }

    @Test
    void allArtifactsHaveServiceCodeType() {
        stubLlm(MOCK_LLM_RESPONSE);
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, List.of());
        result.artifacts().forEach(a ->
                assertThat(a.getArtifactType()).isEqualTo(ArtifactType.SERVICE_CODE));
    }

    @Test
    void filePathIsStoredOnArtifact() {
        stubLlm(MOCK_LLM_RESPONSE);
        ServiceGenerationResult result = agent.generate(1L, OWNER_BOUNDARY, List.of());
        List<String> paths = result.artifacts().stream().map(Artifact::getFilePath).toList();
        assertThat(paths).contains("pom.xml");
    }

    @Test
    void taskTypeIsServiceGen() {
        stubLlm(MOCK_LLM_RESPONSE);
        agent.generate(1L, OWNER_BOUNDARY, List.of());
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getTaskType()).isEqualTo("SERVICE_GEN");
    }

    @Test
    void marksTaskFailedWhenLlmThrows() {
        when(chatModel.generate(anyList())).thenThrow(new RuntimeException("timeout"));
        assertThatThrownBy(() -> agent.generate(1L, OWNER_BOUNDARY, List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Service generation failed");
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().stream()
                .anyMatch(t -> AgentTaskStatus.FAILED.equals(t.getStatus()))).isTrue();
    }

    @Test
    void marksTaskFailedWhenLlmReturnsNoFiles() {
        stubLlm("Here is the service!\n(no file blocks)");
        assertThatThrownBy(() -> agent.generate(1L, OWNER_BOUNDARY, List.of()))
                .isInstanceOf(RuntimeException.class);
    }

    // -----------------------------------------------------------------
    // toPackageName
    // -----------------------------------------------------------------

    @Test
    void ownerServiceBecomesOwnerservice() {
        assertThat(ServiceGeneratorAgent.toPackageName("owner-service")).isEqualTo("ownerservice");
    }

    @Test
    void vetServiceBecomesVetservice() {
        assertThat(ServiceGeneratorAgent.toPackageName("vet-service")).isEqualTo("vetservice");
    }
}
