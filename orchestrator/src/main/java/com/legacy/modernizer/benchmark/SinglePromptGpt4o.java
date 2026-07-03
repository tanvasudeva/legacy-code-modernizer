package com.legacy.modernizer.benchmark;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Phase 4.2 — GPT-4o single-prompt baseline.
 *
 * <p>Sends the full monolith source code (up to 120k tokens of context) in a
 * single prompt to GPT-4o and asks it to decompose the monolith into
 * microservices.  Uses no RAG, no multi-turn dialogue, and no structured output
 * enforcement — exactly the "naive" single-call approach the multi-agent system
 * is benchmarked against.
 *
 * <p>Requires {@code OPENAI_API_KEY} to be set in the environment.  If absent,
 * {@link #run} returns a failed {@link BaselineResult} immediately.
 */
@Component
public class SinglePromptGpt4o extends SinglePromptBaseline {

    private static final Logger log = LoggerFactory.getLogger(SinglePromptGpt4o.class);

    /** GPT-4o context window.  We reserve 8k for the prompt template and response. */
    private static final int CONTEXT_TOKENS = 120_000;

    /** Max output tokens for the service-boundary JSON. */
    private static final int MAX_OUTPUT_TOKENS = 4_096;

    @Value("${OPENAI_API_KEY:${openai.api.key:}}")
    private String apiKey;

    private ChatLanguageModel gpt4oModel;
    private boolean           available;

    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[gpt4o-baseline] OPENAI_API_KEY not set — baseline will return skipped results");
            available = false;
            return;
        }
        gpt4oModel = OpenAiChatModel.builder()
                .apiKey(apiKey.strip())
                .modelName(OpenAiChatModelName.GPT_4_O)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(300))
                .build();
        available = true;
        log.info("[gpt4o-baseline] Initialised — model={}", OpenAiChatModelName.GPT_4_O);
    }

    // ─── Template-method implementations ─────────────────────────────────────

    @Override
    protected ChatLanguageModel model() { return gpt4oModel; }

    @Override
    protected String systemId() { return "single-prompt-gpt4o"; }

    @Override
    protected String modelId()  { return OpenAiChatModelName.GPT_4_O.toString(); }

    @Override
    protected int contextWindowTokens() { return CONTEXT_TOKENS; }

    // ─── Override run to guard on availability ────────────────────────────────

    @Override
    public BaselineResult run(BenchmarkSpec spec, java.nio.file.Path projectRoot,
                              java.nio.file.Path resultsRoot) {
        if (!available) {
            log.warn("[gpt4o-baseline] Skipping {} — OPENAI_API_KEY not set", spec.name());
            return new BaselineResult(
                    spec.name(), systemId(), modelId(), false,
                    null, 0, 0, 0, null, 0, -1,
                    "OPENAI_API_KEY not set",
                    java.time.LocalDateTime.now());
        }
        return super.run(spec, projectRoot, resultsRoot);
    }
}
