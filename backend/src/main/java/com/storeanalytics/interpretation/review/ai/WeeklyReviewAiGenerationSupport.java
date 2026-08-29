package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.generation.LlmProviderRegistry;
import org.springframework.stereotype.Component;

@Component
public record WeeklyReviewAiGenerationSupport(
        WeeklyReviewAiProviderRequestFactory requestFactory,
        WeeklyReviewAiSemanticValidator validator,
        WeeklyReviewAiBudgetGuard budgetGuard,
        WeeklyReviewAiCompletionService completionService,
        LlmProviderRegistry providerRegistry,
        WeeklyReviewAiGenerationProperties properties
) {
}
