package com.storeanalytics.integration.llm.yandex;

import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Network-free Yandex request validation, token estimate and cost bound. */
@Component
public final class YandexLlmRequestPreflight {

    private static final int TOKEN_ESTIMATE_OVERHEAD = 64;
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final String COST_CURRENCY = "RUB";

    private final YandexLlmProperties properties;
    private final YandexLlmPolicyProperties policy;
    private final ObjectMapper objectMapper;
    private final YandexLlmMetrics metrics;

    public YandexLlmRequestPreflight(
            YandexLlmProperties properties,
            YandexLlmPolicyProperties policy,
            ObjectMapper objectMapper,
            YandexLlmMetrics metrics
    ) {
        this.properties = java.util.Objects.requireNonNull(
                properties, "properties"
        );
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.objectMapper = java.util.Objects.requireNonNull(
                objectMapper, "objectMapper"
        );
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
    }

    public LlmProviderPreflight evaluate(LlmProviderRequest request) {
        validateModelConfiguration(request);
        JsonNode responseSchema = parseJson(
                request.responseSchemaJson(),
                "Yandex response schema is not valid JSON"
        );
        if (!responseSchema.isObject()) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    "Yandex response schema must be a JSON object",
                    null
            );
        }
        int estimatedInputTokens = estimateInputTokens(request);
        BigDecimal maximumCost = calculateCost(
                estimatedInputTokens, 0, request.maxOutputTokens()
        );
        metrics.preflight();
        return new LlmProviderPreflight(
                estimatedInputTokens,
                policy.contextWindowTokens(),
                maximumCost,
                COST_CURRENCY
        );
    }

    void validateModelConfiguration(LlmProviderRequest request) {
        if (properties.getFolderId().isBlank()
                || properties.getModelUri().isBlank()) {
            throw notSent(
                    LlmProviderFailureKind.AUTHENTICATION,
                    "Yandex folder and model must be configured",
                    null
            );
        }
        rejectHeaderInjection(properties.getFolderId(), "folder ID");
        String expectedPrefix = "gpt://" + properties.getFolderId() + "/";
        if (!request.requestedModel().startsWith(expectedPrefix)
                || !request.requestedModel().equals(properties.getModelUri())
                || request.requestedModel().endsWith("/latest")) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    "Yandex model URI does not match the versioned configuration",
                    null
            );
        }
    }

    BigDecimal calculateCost(int input, int cached, int output) {
        int uncached = input - cached;
        return policy.inputRubPerThousandTokens()
                .multiply(BigDecimal.valueOf(uncached))
                .add(policy.cachedInputRubPerThousandTokens()
                        .multiply(BigDecimal.valueOf(cached)))
                .add(policy.outputRubPerThousandTokens()
                        .multiply(BigDecimal.valueOf(output)))
                .divide(THOUSAND, 6, RoundingMode.CEILING);
    }

    private void rejectHeaderInjection(String value, String field) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    "Yandex " + field + " contains forbidden characters",
                    null
            );
        }
    }

    private int estimateInputTokens(LlmProviderRequest request) {
        long codePoints = (long) codePoints(request.systemPrompt())
                + codePoints(request.inputJson())
                + codePoints(request.responseSchemaJson());
        long estimate = Math.ceilDiv(codePoints, 2)
                + TOKEN_ESTIMATE_OVERHEAD;
        if (estimate > Integer.MAX_VALUE) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    "Yandex LLM token estimate exceeds supported range",
                    null
            );
        }
        return (int) estimate;
    }

    private int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private JsonNode parseJson(String value, String safeMessage) {
        try {
            return objectMapper.readTree(value);
        } catch (RuntimeException exception) {
            throw notSent(
                    LlmProviderFailureKind.INVALID_REQUEST,
                    safeMessage,
                    exception
            );
        }
    }

    private YandexLlmProviderException notSent(
            LlmProviderFailureKind kind,
            String message,
            Throwable cause
    ) {
        return new YandexLlmProviderException(
                kind,
                LlmProviderOutcomeCertainty.NOT_SENT,
                message,
                null,
                null,
                cause
        );
    }
}
