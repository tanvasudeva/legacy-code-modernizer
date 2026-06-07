package com.legacy.modernizer.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class DocGenAgentTest {

    @Mock ChatLanguageModel   chatModel;
    @Mock AgentTaskRepository taskRepository;
    @Mock ArtifactRepository  artifactRepository;

    DocGenAgent agent;
    AtomicLong idSeq = new AtomicLong(1);

    private static final ServiceBoundary OWNER_BOUNDARY = ServiceBoundary.builder()
            .id(10L).jobId(1L).serviceName("owner-service")
            .description("Manages owner registration and lookup.")
            .rationale("Owner entity forms a cohesive bounded context.")
            .classFqns(List.of("org.petclinic.owner.Owner", "org.petclinic.owner.OwnerController"))
            .build();

    // Minimal valid mock JSON response
    private static final String MOCK_JSON_RESPONSE = """
            {
              "documents": [
                {
                  "type": "openapi_spec",
                  "filename": "openapi.yaml",
                  "content": "openapi: 3.0.3\\ninfo:\\n  title: Owner API\\n  version: 1.0.0\\npaths:\\n  /api/owners:\\n    get:\\n      summary: List owners\\n      responses:\\n        '200':\\n          description: OK"
                },
                {
                  "type": "adr",
                  "filename": "adr-001-owner-service.md",
                  "content": "# ADR-001: Extract Owner Service\\n## Status\\nAccepted\\n## Context\\nLegacy monolith.\\n## Decision\\nExtract as DDD bounded context.\\n## Consequences\\nPositive: independent deployability.\\n## Alternatives Considered\\nKeep in monolith."
                },
                {
                  "type": "runbook",
                  "filename": "runbook-owner-service.md",
                  "content": "# Migration Runbook: Owner Service\\n## Pre-conditions\\n- DB migration applied\\n## Deployment Steps\\n1. Build image\\n## Verification Steps\\n- curl /api/owners\\n## Rollback Procedure\\n1. Redeploy monolith"
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        agent = new DocGenAgent(chatModel, taskRepository, artifactRepository, new ObjectMapper());

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
        Response<AiMessage> r = Response.from(AiMessage.from(text), new TokenUsage(600, 2200));
        when(chatModel.generate(anyList())).thenReturn(r);
    }

    // -----------------------------------------------------------------
    // JSON parsing
    // -----------------------------------------------------------------

    @Test
    void parsesThreeDocsFromValidJson() throws Exception {
        List<DocGenAgent.DocItem> items = agent.parseJsonResponse(MOCK_JSON_RESPONSE);
        assertThat(items).hasSize(3);
    }

    @Test
    void firstDocIsOpenApiSpec() throws Exception {
        List<DocGenAgent.DocItem> items = agent.parseJsonResponse(MOCK_JSON_RESPONSE);
        assertThat(items.get(0).type()).isEqualTo("openapi_spec");
        assertThat(items.get(0).filename()).isEqualTo("openapi.yaml");
    }

    @Test
    void openApiContentContainsOpenapiKey() throws Exception {
        List<DocGenAgent.DocItem> items = agent.parseJsonResponse(MOCK_JSON_RESPONSE);
        assertThat(items.get(0).content()).contains("openapi: 3.0.3");
    }

    @Test
    void adrContentContainsStatusAccepted() throws Exception {
        List<DocGenAgent.DocItem> items = agent.parseJsonResponse(MOCK_JSON_RESPONSE);
        assertThat(items.get(1).content()).contains("Accepted");
    }

    @Test
    void jsonWrappedInCodeFenceIsStillParsed() throws Exception {
        String fenced = "```json\n" + MOCK_JSON_RESPONSE + "\n```";
        assertThat(agent.parseJsonResponse(fenced)).hasSize(3);
    }

    @Test
    void emptyDocumentsListReturnsEmpty() throws Exception {
        String empty = "{\"documents\":[]}";
        assertThat(agent.parseJsonResponse(empty)).isEmpty();
    }

    // -----------------------------------------------------------------
    // resolveArtifactType
    // -----------------------------------------------------------------

    @Test
    void openapiSpecResolvesToOpenApiSpec() {
        assertThat(DocGenAgent.resolveArtifactType("openapi_spec")).isEqualTo(ArtifactType.OPENAPI_SPEC);
    }

    @Test
    void adrResolvesToAdr() {
        assertThat(DocGenAgent.resolveArtifactType("adr")).isEqualTo(ArtifactType.ADR);
    }

    @Test
    void runbookResolvesToRunbook() {
        assertThat(DocGenAgent.resolveArtifactType("runbook")).isEqualTo(ArtifactType.RUNBOOK);
    }

    @Test
    void unknownTypeResolvesToConfig() {
        assertThat(DocGenAgent.resolveArtifactType("unknown")).isEqualTo(ArtifactType.CONFIG);
    }

    // -----------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------

    @Test
    void taskIsCompletedOnSuccess() {
        stubLlm(MOCK_JSON_RESPONSE);
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                "", "", "", List.of());
        assertThat(r.task().getStatus()).isEqualTo(AgentTaskStatus.COMPLETED);
    }

    @Test
    void threeArtifactsAreStored() {
        stubLlm(MOCK_JSON_RESPONSE);
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                "", "", "", List.of());
        assertThat(r.artifacts()).hasSize(3);
        assertThat(r.files()).hasSize(3);
    }

    @Test
    void artifactTypesAreCorrect() {
        stubLlm(MOCK_JSON_RESPONSE);
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                "", "", "", List.of());
        List<ArtifactType> types = r.artifacts().stream().map(Artifact::getArtifactType).toList();
        assertThat(types).containsExactlyInAnyOrder(
                ArtifactType.OPENAPI_SPEC, ArtifactType.ADR, ArtifactType.RUNBOOK);
    }

    @Test
    void filePathPrefixedWithDocs() {
        stubLlm(MOCK_JSON_RESPONSE);
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                "", "", "", List.of());
        r.files().forEach(f -> assertThat(f.filePath()).startsWith("docs/"));
    }

    @Test
    void taskTypeIsDocGen() {
        stubLlm(MOCK_JSON_RESPONSE);
        agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner", "", "", "", List.of());
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getTaskType()).isEqualTo("DOC_GEN");
    }

    @Test
    void openApiContentIsUnescaped() {
        stubLlm(MOCK_JSON_RESPONSE);
        DocGenerationResult r = agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                "", "", "", List.of());
        String openapi = r.files().stream()
                .filter(f -> f.filePath().endsWith("openapi.yaml"))
                .findFirst().orElseThrow().content();
        // JSON \n → actual newline in stored content
        assertThat(openapi).contains("\n");
        assertThat(openapi).contains("openapi: 3.0.3");
    }

    // -----------------------------------------------------------------
    // Error path
    // -----------------------------------------------------------------

    @Test
    void marksTaskFailedOnLlmError() {
        when(chatModel.generate(anyList())).thenThrow(new RuntimeException("timeout"));
        assertThatThrownBy(() -> agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                "", "", "", List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Doc generation failed");
        ArgumentCaptor<AgentTask> cap = ArgumentCaptor.forClass(AgentTask.class);
        verify(taskRepository, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().stream()
                .anyMatch(t -> AgentTaskStatus.FAILED.equals(t.getStatus()))).isTrue();
    }

    @Test
    void marksTaskFailedOnInvalidJson() {
        stubLlm("not json at all, sorry");
        assertThatThrownBy(() -> agent.generate(1L, OWNER_BOUNDARY, "ownerservice", "Owner",
                "", "", "", List.of()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void promptContainsServiceName() {
        String prompt = agent.buildUserPrompt(OWNER_BOUNDARY, "ownerservice", "Owner",
                "", "", "", List.of());
        assertThat(prompt).contains("owner-service").contains("ownerservice").contains("Owner");
    }
}
