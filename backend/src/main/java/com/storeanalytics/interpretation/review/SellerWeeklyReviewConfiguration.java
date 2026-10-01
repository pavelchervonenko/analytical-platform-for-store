package com.storeanalytics.interpretation.review;

import org.springframework.stereotype.Component;

/** Fail fast instead of silently disabling both planners in an invalid cutover combination. */
@Component
final class SellerWeeklyReviewConfiguration {
    SellerWeeklyReviewConfiguration(SellerWeeklyReviewProperties sellers, WeeklyReviewProperties reviews) {
        if (sellers.enabled() && !reviews.enabled()) {
            throw new IllegalStateException("Seller weekly review requires the parent weekly review feature");
        }
    }
}
