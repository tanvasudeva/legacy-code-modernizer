package com.legacy.modernizer.benchmark;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Phase 4.2 — Claude claude-sonnet-4-6 single-prompt baseline.
 *
 * <p>Sends the full monolith source code (up to 190k tokens of context) in a
 * single prompt to {@code claude-sonnet-4-6} and asks it to decompose the
 * monolith into microservices.  No RAG, no multi-turn, no structured output
 * enforcement — the same "naive" single-call approach used for the GPT-4o
 * baseline.
 *
 * <p>This baseline uses a <em>dedicated</em> {@link AnthropicChatModel} instance
 * with a generous {@code maxTokens} budget, separate from the shared
 * {@code ChatLanguageModel} bean used by the multi-agent pipeline.
 *
 * <p>Requires {@code ANTHROPIC_API_KEY} to be set in the environment.  If absent,
 * {@link #run} returns a failed {@link BaselineResult} immediately.
 */
@Component
public class SinglePromptClaude extends SinglePromptBaseline {

    private static final Logger log = LoggerFactory.getLogger(SinglePromptClaude.class);

    static final String MODEL_ID = "claude-sonnet-4-6";

    /** claude-sonnet-4-6 has a 200k-token context window.  Reserve 10k for response. */
    private static final int CONTEXT_TOKENS    = 190_000;
    private static final int MAX_OUTPUT_TOKENS = 8_192;

    @Value("${ANTHROPIC_API_KEY:${anthropic.api.key:}}")
    private String apiKey;

    private ChatLanguageModel claudeModel;
    private boolean           available;

    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[claude-baseline] ANTHROPIC_API_KEY not set — baseline will return skipped results");
            available = false;
            return;
        }
        claudeModel = AnthropicChatModel.builder()
                .apiKey(apiKey.strip())
                .modelName(MODEL_ID)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(300))
                .build();
        available = true;
        log.info("[claude-baseline] Initialised — model={}", MODEL_ID);
    }

    // ─── Template-method implementations ─────────────────────────────────────

    @Override
    protected ChatLanguageModel model() { return claudeModel; }

    @Override
    protected String systemId() { return "single-prompt-claude"; }

    @Override
    protected String modelId()  { return MODEL_ID; }

    @Override
    protected int contextWindowTokens() { return CONTEXT_TOKENS; }

    // ─── Override run to guard on availability ────────────────────────────────

    @Override
    public BaselineResult run(BenchmarkSpec spec, java.nio.file.Path projectRoot,
                              java.nio.file.Path resultsRoot) {
        if (!available) {
            log.warn("[claude-baseline] Skipping {} — ANTHROPIC_API_KEY not set", spec.name());
            return new BaselineResult(
                    spec.name(), systemId(), modelId(), false,
                    null, 0, 0, 0, null, 0, -1,
                    "ANTHROPIC_API_KEY not set",
                    LocalDateTime.now());
        }
        return super.run(spec, projectRoot, resultsRoot);
    }
}
