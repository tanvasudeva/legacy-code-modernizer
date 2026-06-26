package com.legacy.modernizer.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Wires Gemini via Google's OpenAI-compatible endpoint so no extra dependency is needed.
 * Active only when GEMINI_API_KEY is present. Takes priority over Anthropic and Ollama.
 *
 * Get a free key at: https://aistudio.google.com/app/apikey
 * Then: export GEMINI_API_KEY=AIza...
 */
@Configuration
@ConditionalOnExpression("T(java.lang.System).getenv('GEMINI_API_KEY') != null")
public class GeminiConfig {

    private static final Logger log = LoggerFactory.getLogger(GeminiConfig.class);

    private static final String GEMINI_OPENAI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai/";

    @Value("${gemini.model:gemini-2.0-flash}")
    private String model;

    @Value("${gemini.max-output-tokens:8192}")
    private int maxOutputTokens;

    @Bean
    @Primary
    public ChatLanguageModel geminiChatModel() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        log.info("Gemini ChatLanguageModel active — model={} via OpenAI-compat endpoint", model);
        return OpenAiChatModel.builder()
                .baseUrl(GEMINI_OPENAI_BASE_URL)
                .apiKey(apiKey)
                .modelName(model)
                .maxTokens(maxOutputTokens)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(600))
                .build();
    }
}
