package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNullableNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

public record LlmProviderResponseReceipt(
        String responseBody,
        String resolvedModel,
        String providerRequestId,
        Integer inputTokens,
        Integer outputTokens,
        Integer cachedInputTokens,
        Integer reasoningTokens,
        Integer totalTokens,
        BigDecimal costAmount,
        String costCurrency,
        Long latencyMs,
        Integer httpStatus
) {

    private static final int MAX_RESPONSE_BYTES = 1_048_576;

    public LlmProviderResponseReceipt {
        requireText(responseBody, "responseBody");
        require(responseBody.getBytes(StandardCharsets.UTF_8).length <= MAX_RESPONSE_BYTES,
                "responseBody must not exceed 1048576 UTF-8 bytes");
        requireNullableText(resolvedModel, "resolvedModel");
        requireNullableText(providerRequestId, "providerRequestId");
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(cachedInputTokens, "cachedInputTokens");
        requireNonNegative(reasoningTokens, "reasoningTokens");
        requireNonNegative(totalTokens, "totalTokens");
        costAmount = requireNullableNonNegative(costAmount, "costAmount", 19, 6);
        require((costAmount == null) == (costCurrency == null),
                "costAmount and costCurrency must be supplied together");
        if (costCurrency != null) {
            require(costCurrency.matches("[A-Z]{3}"),
                    "costCurrency must be a three-letter uppercase code");
        }
        requireNonNegative(latencyMs, "latencyMs");
        require(httpStatus == null || httpStatus >= 100 && httpStatus <= 599,
                "httpStatus must be between 100 and 599");
    }

    private static void requireNullableText(String value, String field) {
        require(value == null || !value.isBlank(), field + " must not be blank");
    }

    private static void requireNonNegative(Integer value, String field) {
        require(value == null || value >= 0, field + " must not be negative");
    }

    private static void requireNonNegative(Long value, String field) {
        require(value == null || value >= 0, field + " must not be negative");
    }
}
