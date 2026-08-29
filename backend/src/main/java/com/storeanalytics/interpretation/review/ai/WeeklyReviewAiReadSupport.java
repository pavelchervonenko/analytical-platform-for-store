package com.storeanalytics.interpretation.review.ai;

import org.springframework.stereotype.Component;

/** Cohesive optional-AI read path kept separate from deterministic report generation. */
@Component
public record WeeklyReviewAiReadSupport(
        WeeklyReviewAiEnrichmentStore enrichmentStore,
        WeeklyReviewAiEnricher enricher,
        WeeklyReviewAiStateResolver stateResolver,
        WeeklyReviewAiGenerationProperties properties
) {
}
