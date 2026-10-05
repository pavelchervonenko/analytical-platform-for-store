package com.storeanalytics.interpretation.review;

import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiGenerationProperties;
import org.springframework.stereotype.Component;

/** Never infer baseline or silently fall back to a current-roster planner in historical mode. */
@Component
final class SellerWeeklyPreparationConfiguration {
    SellerWeeklyPreparationConfiguration(SellerWeeklyPreparationProperties preparation,
            SellerWeeklyReviewProperties sellers, WeeklyReviewProperties reviews,
            WeeklyReviewAiGenerationProperties ai) {
        if (preparation.enabled() && (!sellers.enabled() || !reviews.enabled())) {
            throw new IllegalStateException("Historical preparation requires parent and seller weekly review features");
        }
        if (preparation.enabled() && ai.plannerEnabled()) {
            throw new IllegalStateException("Historical preparation cannot use the current-roster paid AI planner");
        }
    }
}
