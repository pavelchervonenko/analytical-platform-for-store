package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public final class WeeklyReviewAiBudgetGuard {

    private final WeeklyReviewAiGenerationProperties properties;

    public WeeklyReviewAiBudgetGuard(
            WeeklyReviewAiGenerationProperties properties
    ) {
        this.properties = properties;
    }

    public void validate(
            LlmProviderRequest request,
            LlmProviderPreflight preflight,
            BigDecimal actualCostToday
    ) {
        int requestBytes = bytes(request.systemPrompt())
                + bytes(request.inputJson())
                + bytes(request.responseSchemaJson());
        if (requestBytes > properties.maxRequestBytes()) {
            throw new WeeklyReviewAiBudgetException(
                    "REQUEST_TOO_LARGE",
                    "Weekly review AI request exceeds byte budget"
            );
        }
        long totalTokens = (long) preflight.estimatedInputTokens()
                + request.maxOutputTokens();
        if (totalTokens > preflight.contextWindowTokens()) {
            throw new WeeklyReviewAiBudgetException(
                    "CONTEXT_WINDOW_EXCEEDED",
                    "Weekly review AI request exceeds provider context window"
            );
        }
        if (!"RUB".equals(preflight.costCurrency())) {
            throw new WeeklyReviewAiBudgetException(
                    "CURRENCY_UNSUPPORTED",
                    "Weekly review AI provider currency is unsupported"
            );
        }
        if (preflight.estimatedMaximumCost().compareTo(
                properties.maxEstimatedCostRub()
        ) > 0) {
            throw new WeeklyReviewAiBudgetException(
                    "CALL_BUDGET_EXCEEDED",
                    "Weekly review AI request exceeds per-call budget"
            );
        }
        BigDecimal projected = actualCostToday.add(
                preflight.estimatedMaximumCost()
        );
        if (projected.compareTo(properties.dailyCostLimitRub()) > 0) {
            throw new WeeklyReviewAiBudgetException(
                    "DAILY_BUDGET_EXCEEDED",
                    "Weekly review AI request exceeds daily budget"
            );
        }
    }

    private int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
