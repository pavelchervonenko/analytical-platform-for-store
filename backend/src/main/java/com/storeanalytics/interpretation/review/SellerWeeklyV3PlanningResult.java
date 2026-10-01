package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

/** Evaluation is not publication; freshness is independently assessed after a write. */
record SellerWeeklyV3PlanningResult(Outcome outcome, SellerWeeklyV3ReadResult review) {

    enum Outcome {
        UNCHANGED,
        EVALUATED,
        DEFERRED
    }

    SellerWeeklyV3PlanningResult {
        requireNonNull(outcome, "outcome");
        requireNonNull(review, "review");
        if (outcome == Outcome.UNCHANGED && review.state() != SellerWeeklyV3ReadResult.State.CURRENT) {
            throw new IllegalArgumentException("Unchanged planning result requires current v3 state");
        }
    }
}
