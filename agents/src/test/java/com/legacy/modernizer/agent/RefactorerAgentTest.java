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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefactorerAgentTest {

    @Mock ChatLanguageModel chatModel;
    @Mock AgentTaskRepository taskRepository;
    @Mock ArtifactRepository artifactRepository;

    RefactorerAgent agent;

    // Incremental ID sequence for saved entities
    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        agent = new RefactorerAgent(chatModel, taskRepository, artifactRepository);

        // taskRepository.save() returns the entity with a new id
        when(taskRepository.save(any(AgentTask.class))).thenAnswer(inv -> {
            AgentTask t = inv.getArgument(0);
            if (t.getId() == null) t = AgentTask.builder()
                    .id(idSeq.getAndIncrement())
                    .jobId(t.getJobId())
                    .taskType(t.getTaskType())
                    .status(t.getStatus())
                    .classFqn(t.getClassFqn())
                    .inputData(t.getInputData())
                    .outputData(t.getOutputData())
                    .modelUsed(t.getModelUsed())
                    .tokensUsed(t.getTokensUsed())
                    .errorMessage(t.getErrorMessage())
                    .build();
            return t;
        });
        when(artifactRepository.save(any(Artifact.class))).thenAnswer(inv -> {
            Artifact a = inv.getArgument(0);
            if (a.getId() == null) a = Artifact.builder()
                    .id(idSeq.getAndIncrement())
                    .jobId(a.getJobId())
                    .taskId(a.getTaskId())
                    .artifactType(a.getArtifactType())
                    .classFqn(a.getClassFqn())
                    .filePath(a.getFilePath())
                    .content(a.getContent())
                    .checksum(a.getChecksum())
                    .build();
            return a;
        });
    }

    private void stubLlm(String responseText, int totalTokens) {
        TokenUsage usage = new TokenUsage(totalTokens / 2, totalTokens / 2);
        Response<AiMessage> response = Response.from(AiMessage.from(responseText), usage);
        when(chatModel.generate(anyList())).thenReturn(response);
    }

    // -----------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------

    @Test
    void returnsCompletedResultOnSuccess() {
        stubLlm("package com.example;\npublic class OwnerController {}", 300);

        RefactorResult result = agent.refactor(1L, "com.example.OwnerController",
                "package com.example;\npublic class OwnerController {}",
                "owner-service", "Manages owner domain", null);

        assertThat(result).isNotNull();
        assertThat(result.task().getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
        assertThat(result.original()).isNotNull();
        assertThat(result.transformed()).isNotNull();
    }

    @Test
    void taskTypeIsTransform() {
        stubLlm("transformed", 100);

        agent.refactor(1L, "com.example.Foo", "class Foo {}", "svc", "ctx", null);

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository, atLeastOnce()).save(captor.capture());
        List<AgentTask> saved = captor.getAllValues();
        assertThat(saved.get(0).getTaskType()).isEqualTo("TRANSFORM");
    }

    @Test
    void originalArtifactHasCorrectType() {
        stubLlm("transformed", 100);

        RefactorResult result = agent.refactor(1L, "com.example.Foo", "class Foo {}",
                "svc", "ctx", "src/Foo.java");

        assertThat(result.original().getArtifactType()).isEqualTo(ArtifactType.ORIGINAL);
        assertThat(result.original().getContent()).isEqualTo("class Foo {}");
    }

    @Test
    void transformedArtifactHasCorrectType() {
        stubLlm("class FooRefactored {}", 100);

        RefactorResult result = agent.refactor(1L, "com.example.Foo", "class Foo {}",
                "svc", "ctx", null);

        assertThat(result.transformed().getArtifactType()).isEqualTo(ArtifactType.TRANSFORMED);
        assertThat(result.transformed().getContent()).isEqualTo("class FooRefactored {}");
    }

    @Test
    void checksumIsNonNullForBothArtifacts() {
        stubLlm("class Transformed {}", 100);

        RefactorResult result = agent.refactor(1L, "com.example.Foo", "class Foo {}",
                "svc", "ctx", null);

        assertThat(result.original().getChecksum()).isNotBlank();
        assertThat(result.transformed().getChecksum()).isNotBlank();
        assertThat(result.original().getChecksum())
                .isNotEqualTo(result.transformed().getChecksum());
    }

    @Test
    void tokenUsageIsRecordedWhenAvailable() {
        stubLlm("transformed", 420);

        RefactorResult result = agent.refactor(1L, "com.example.Foo", "class Foo {}",
                "svc", "ctx", null);

        assertThat(result.task().getTokensUsed()).isEqualTo(420);
    }

    @Test
    void markdownCodeFencesAreStripped() {
        stubLlm("```java\npublic class Foo {}\n```", 100);

        RefactorResult result = agent.refactor(1L, "com.example.Foo", "class Foo {}",
                "svc", "ctx", null);

        assertThat(result.transformed().getContent()).isEqualTo("public class Foo {}");
    }

    // -----------------------------------------------------------------
    // Error path
    // -----------------------------------------------------------------

    @Test
    void marksTaskFailedWhenLlmThrows() {
        when(chatModel.generate(anyList())).thenThrow(new RuntimeException("LLM timeout"));

        assertThatThrownBy(() ->
                agent.refactor(1L, "com.example.Foo", "class Foo {}", "svc", "ctx", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Refactoring failed");

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository, atLeastOnce()).save(captor.capture());
        boolean anyFailed = captor.getAllValues().stream()
                .anyMatch(t -> AgentTaskStatus.FAILED.equals(t.getStatus()));
        assertThat(anyFailed).isTrue();
    }

    @Test
    void originalArtifactIsPersistedEvenOnLlmFailure() {
        when(chatModel.generate(anyList())).thenThrow(new RuntimeException("timeout"));

        assertThatThrownBy(() ->
                agent.refactor(1L, "com.example.Foo", "class Foo {}", "svc", "ctx", null));

        ArgumentCaptor<Artifact> captor = ArgumentCaptor.forClass(Artifact.class);
        verify(artifactRepository).save(captor.capture());
        assertThat(captor.getValue().getArtifactType()).isEqualTo(ArtifactType.ORIGINAL);
    }
}
