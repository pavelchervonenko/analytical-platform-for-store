package com.storeanalytics.interpretation.config;

import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.llm")
public record LlmGenerationProperties(
        @DefaultValue("weekly-interpretation-v4") String promptVersion,
        @DefaultValue("2") int contentSchemaVersion,
        @DefaultValue("0.2") BigDecimal temperature,
        @DefaultValue("8000") int maxOutputTokens,
        @DefaultValue("2") int maxProviderCalls
) {
    public LlmGenerationProperties {
        Objects.requireNonNull(promptVersion, "promptVersion");
        Objects.requireNonNull(temperature, "temperature");
        if (promptVersion.isBlank()) {
            throw new IllegalArgumentException("LLM promptVersion must not be blank");
        }
        if (contentSchemaVersion < 1) {
            throw new IllegalArgumentException("LLM contentSchemaVersion must be positive");
        }
        if (temperature.compareTo(BigDecimal.ZERO) < 0
                || temperature.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("LLM temperature must be between 0 and 1");
        }
        if (maxOutputTokens < 512 || maxOutputTokens > 16_000) {
            throw new IllegalArgumentException(
                    "LLM maxOutputTokens must be between 512 and 16000"
            );
        }
        if (maxProviderCalls < 1 || maxProviderCalls > 2) {
            throw new IllegalArgumentException("LLM maxProviderCalls must be 1 or 2");
        }
    }
}
