package com.storeanalytics.interpretation.review.ai;

import org.springframework.stereotype.Component;

/** Cohesive optional-AI dependencies; no provider calls occur in the reader. */
@Component
public record SellerWeeklyReviewAiReadSupport(
        WeeklyReviewAiGenerationProperties properties,
        WeeklyReviewAiEnrichmentStore enrichments,
        SellerWeeklyReviewAiEnricher enricher,
        SellerWeeklyReviewAiStateResolver states
) {
}
