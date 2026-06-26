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
@ConditionalOnExpression("T(java.lang.System).getenv('ANTHROPIC_API_KEY') != null and T(java.lang.System).getenv('GEMINI_API_KEY') == null")
public class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    @Value("${anthropic.model:claude-sonnet-4-6}")
    private String model;

    @Value("${anthropic.max-tokens:4096}")
    private int maxTokens;

    @Bean
    @Primary
    public ChatLanguageModel anthropicChatModel() {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
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
