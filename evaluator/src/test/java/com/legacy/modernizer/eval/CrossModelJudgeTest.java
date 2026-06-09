package com.legacy.modernizer.eval;

import com.legacy.modernizer.eval.metric.CrossModelJudge;
import com.legacy.modernizer.eval.metric.LlmJudgeMetric;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CrossModelJudge} routing logic.
 *
 * Verifies the core methodological requirement: no model judges its own output.
 *   - multi-agent output (Claude-generated)   → GPT-4o mock is called
 *   - single-prompt-gpt4o output              → Claude mock is called
 *   - single-prompt-claude output             → GPT-4o mock is called
 *
 * Both LLM clients are mocked — no real API calls.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrossModelJudgeTest {

    @Mock ChatLanguageModel mockClaudeModel;
    @Mock ChatLanguageModel mockGpt4oModel;

    CrossModelJudge judge;

    private static final String SCORES_JSON =
            "{\"correctness\":8,\"readability\":7,\"idiomaticity\":8,\"completeness\":7,\"dry\":8}";

    @BeforeEach
    void setUp() {
        judge = new CrossModelJudge();
        // Inject mocks directly without @PostConstruct
        ReflectionTestUtils.setField(judge, "claudeModel", mockClaudeModel);
        ReflectionTestUtils.setField(judge, "gpt4oModel",  mockGpt4oModel);

        Response<AiMessage> fakeResponse =
                Response.from(AiMessage.from(SCORES_JSON), new TokenUsage(50, 200));
        when(mockClaudeModel.generate(anyList())).thenReturn(fakeResponse);
        when(mockGpt4oModel.generate(anyList())).thenReturn(fakeResponse);
    }

    // ─── Judge routing ────────────────────────────────────────────────────────

    @Test
    void multiAgentOutputIsJudgedByGpt4o() {
        judge.judge("multi-agent", "class OwnerService {}", LlmJudgeMetric.CODE_SYSTEM_PROMPT);

        verify(mockGpt4oModel, times(1)).generate(anyList());
        verify(mockClaudeModel, never()).generate(anyList());
    }

    @Test
    void gpt4oBaselineOutputIsJudgedByClaude() {
        judge.judge("single-prompt-gpt4o", "some plan", LlmJudgeMetric.PLAN_SYSTEM_PROMPT);

        verify(mockClaudeModel, times(1)).generate(anyList());
        verify(mockGpt4oModel, never()).generate(anyList());
    }

    @Test
    void claudeBaselineOutputIsJudgedByGpt4o() {
        judge.judge("single-prompt-claude", "some plan", LlmJudgeMetric.PLAN_SYSTEM_PROMPT);

        verify(mockGpt4oModel, times(1)).generate(anyList());
        verify(mockClaudeModel, never()).generate(anyList());
    }

    // ─── resolveJudgeModelId ──────────────────────────────────────────────────

    @Test
    void multiAgentResolvesToGpt4oId() {
        assertThat(judge.resolveJudgeModelId("multi-agent"))
                .isEqualTo(CrossModelJudge.GPT4O_MODEL_ID);
    }

    @Test
    void gpt4oBaselineResolvesToClaudeId() {
        assertThat(judge.resolveJudgeModelId("single-prompt-gpt4o"))
                .isEqualTo(CrossModelJudge.CLAUDE_MODEL_ID);
    }

    @Test
    void claudeBaselineResolvesToGpt4oId() {
        assertThat(judge.resolveJudgeModelId("single-prompt-claude"))
                .isEqualTo(CrossModelJudge.GPT4O_MODEL_ID);
    }

    // ─── JudgeResult content ─────────────────────────────────────────────────

    @Test
    void judgeResultCarriesCorrectModelId() {
        CrossModelJudge.JudgeResult result =
                judge.judge("multi-agent", "class X {}", LlmJudgeMetric.CODE_SYSTEM_PROMPT);

        assertThat(result.judgeModelId()).isEqualTo(CrossModelJudge.GPT4O_MODEL_ID);
    }

    @Test
    void judgeResultScoreIsMeanOfSubScores() {
        CrossModelJudge.JudgeResult result =
                judge.judge("multi-agent", "class X {}", LlmJudgeMetric.CODE_SYSTEM_PROMPT);

        // JSON: {8,7,8,7,8} → mean = 38/5 = 7.6
        assertThat(result.score()).isEqualTo(7.6);
        assertThat(result.subScores()).containsKey("correctness");
    }

    @Test
    void judgeResultMetadataContainsJudgeModel() {
        CrossModelJudge.JudgeResult result =
                judge.judge("single-prompt-gpt4o", "plan text", LlmJudgeMetric.PLAN_SYSTEM_PROMPT);

        assertThat(result.metadata()).containsEntry("judgeModel", CrossModelJudge.CLAUDE_MODEL_ID);
    }

    // ─── Availability guard ───────────────────────────────────────────────────

    @Test
    void judgeIsAvailableWhenEitherModelPresent() {
        assertThat(judge.isAvailable()).isTrue();
    }

    @Test
    void judgeUnavailableWhenBothModelsNull() {
        CrossModelJudge noModels = new CrossModelJudge();
        ReflectionTestUtils.setField(noModels, "claudeModel", null);
        ReflectionTestUtils.setField(noModels, "gpt4oModel",  null);

        assertThat(noModels.isAvailable()).isFalse();
    }

    @Test
    void judgeReturnsZeroScoreWhenUnavailable() {
        CrossModelJudge noModels = new CrossModelJudge();
        ReflectionTestUtils.setField(noModels, "claudeModel", null);
        ReflectionTestUtils.setField(noModels, "gpt4oModel",  null);

        CrossModelJudge.JudgeResult result =
                noModels.judge("multi-agent", "code", LlmJudgeMetric.CODE_SYSTEM_PROMPT);

        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.metadata()).containsKey("reason");
    }

    // ─── LlmJudgeMetric integration (via CrossModelJudge) ─────────────────────

    @Test
    void llmJudgeMetricDelegatesToCrossModelJudge() {
        LlmJudgeMetric metric = new LlmJudgeMetric(judge);
        LlmJudgeMetric.Result result =
                metric.evaluateBaseline("some plan content", "single-prompt-gpt4o");

        // Claude mock should have been called (GPT-4o output → Claude judge)
        verify(mockClaudeModel, atLeastOnce()).generate(anyList());
        assertThat(result.judgeModelId()).isEqualTo(CrossModelJudge.CLAUDE_MODEL_ID);
    }

    @Test
    void llmJudgeMetricResultExposesJudgeModelId() {
        LlmJudgeMetric metric = new LlmJudgeMetric(judge);
        LlmJudgeMetric.Result result =
                metric.evaluateBaseline("plan", "single-prompt-claude");

        // Claude output → GPT-4o judges
        assertThat(result.judgeModelId()).isEqualTo(CrossModelJudge.GPT4O_MODEL_ID);
        assertThat(result.score()).isGreaterThan(0.0);
    }
}
