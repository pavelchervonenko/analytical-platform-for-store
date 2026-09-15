package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.integration.llm.yandex.YandexLlmRequestPreflight;
import org.springframework.stereotype.Component;

/** Collaborators used only by the exact-snapshot operator path. */
@Component
public record WeeklyReviewAiOperatorSupport(
        WeeklyReviewAiEnrichmentStore enrichmentStore,
        WeeklyReviewAiProviderRequestFactory requestFactory,
        WeeklyReviewAiBudgetGuard budgetGuard,
        YandexLlmRequestPreflight requestPreflight,
        YandexLlmProperties yandexProperties
) {
}
