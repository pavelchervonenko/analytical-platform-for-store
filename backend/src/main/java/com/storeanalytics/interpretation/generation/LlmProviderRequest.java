package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireJson;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LlmProviderRequest(
        UUID jobId,
        String providerCode,
        String requestedModel,
        String systemPrompt,
        String inputJson,
        String responseSchemaJson,
        BigDecimal temperature,
        int maxOutputTokens,
        Instant callDeadline
) {

    public LlmProviderRequest {
        requireNonNull(jobId, "jobId");
        requireText(providerCode, "providerCode");
        requireText(requestedModel, "requestedModel");
        requireText(systemPrompt, "systemPrompt");
        requireJson(inputJson, "inputJson");
        requireJson(responseSchemaJson, "responseSchemaJson");
        requireNonNull(temperature, "temperature");
        require(temperature.compareTo(BigDecimal.ZERO) >= 0
                        && temperature.compareTo(BigDecimal.ONE) <= 0,
                "temperature must be between 0 and 1");
        require(maxOutputTokens > 0, "maxOutputTokens must be positive");
        requireNonNull(callDeadline, "callDeadline");
    }
}
