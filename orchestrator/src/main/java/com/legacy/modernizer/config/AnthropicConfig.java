package com.legacy.modernizer.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.time.Duration;

/**
 * Registers a Claude-backed {@link ChatLanguageModel} bean when ANTHROPIC_API_KEY
 * is present in the environment. Overrides the Ollama bean via {@code @Primary}.
 *
 * <p>If the env-var is absent, the Ollama bean from {@link OllamaConfig} is used
 * as the default ChatLanguageModel.
 */
@Configuration
@ConditionalOnExpression(
    "'${ANTHROPIC_API_KEY:${anthropic.api.key:}}'.length() > 0 " +
    "and '${GEMINI_API_KEY:}'.length() == 0"
)
public class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    @Value("${anthropic.model:claude-haiku-4-5-20251001}")
    private String model;

    @Value("${anthropic.max-tokens:8192}")
    private int maxTokens;

    @Value("${ANTHROPIC_API_KEY:${anthropic.api.key:}}")
    private String apiKey;

    @Bean
    @Primary
    public ChatLanguageModel anthropicChatModel() {
        log.info("Anthropic ChatLanguageModel active — model={}", model);
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .maxTokens(maxTokens)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(600))
                .build();
    }
}
