package com.storeanalytics.interpretation.generation;

import com.storeanalytics.interpretation.config.LlmAnalysisWorkerProperties;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LlmProviderBudgetGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            LlmProviderBudgetGuard.class
    );

    private final LlmAnalysisWorkerProperties properties;

    public LlmProviderBudgetGuard(LlmAnalysisWorkerProperties properties) {
        this.properties = properties;
    }

    public void validate(
            LlmProviderRequest request,
            LlmProviderPreflight preflight
    ) {
        LOGGER.info(
                "LLM provider preflight: estimatedInputTokens={}, "
                        + "contextWindowTokens={}, maxOutputTokens={}, "
                        + "estimatedMaximumCost={} {},"
                        + " requestBytes={}",
                preflight.estimatedInputTokens(),
                preflight.contextWindowTokens(),
                request.maxOutputTokens(),
                preflight.estimatedMaximumCost(),
                preflight.costCurrency(),
                bytes(request.systemPrompt())
                        + bytes(request.inputJson())
                        + bytes(request.responseSchemaJson())
        );
        int requestBytes = bytes(request.systemPrompt())
                + bytes(request.inputJson())
                + bytes(request.responseSchemaJson());
        if (requestBytes > properties.maxRequestBytes()) {
            throw new LlmProviderPreflightException(
                    LlmProviderPreflightFailureKind.REQUEST_TOO_LARGE,
                    "LLM request exceeds configured byte budget"
            );
        }
        long totalTokens = (long) preflight.estimatedInputTokens()
                + request.maxOutputTokens();
        if (totalTokens > preflight.contextWindowTokens()) {
            throw new LlmProviderPreflightException(
                    LlmProviderPreflightFailureKind.CONTEXT_WINDOW_EXCEEDED,
                    "LLM request exceeds provider context window"
            );
        }
        if (!"RUB".equals(preflight.costCurrency())) {
            throw new LlmProviderPreflightException(
                    LlmProviderPreflightFailureKind.CURRENCY_UNSUPPORTED,
                    "LLM preflight cost currency is not supported"
            );
        }
        if (preflight.estimatedMaximumCost().compareTo(
                properties.maxEstimatedCostRub()
        ) > 0) {
            throw new LlmProviderPreflightException(
                    LlmProviderPreflightFailureKind.COST_BUDGET_EXCEEDED,
                    "LLM request exceeds configured cost budget"
            );
        }
    }

    private int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
