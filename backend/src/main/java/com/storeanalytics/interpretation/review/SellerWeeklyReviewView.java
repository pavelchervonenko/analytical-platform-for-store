package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

/** Freshness is separate from metric quality; a stale payload is never labelled current. */
public record SellerWeeklyReviewView(Freshness freshness, WeeklyReviewV3Response report) {
    public enum Freshness {
        PREPARING, CURRENT, STALE
    }

    public SellerWeeklyReviewView {
        requireNonNull(freshness, "freshness");
        require((freshness == Freshness.PREPARING) == (report == null),
                "Only preparing reviews may omit the report");
    }

    static SellerWeeklyReviewView from(SellerWeeklyV3ReadResult result) {
        return new SellerWeeklyReviewView(Freshness.valueOf(result.state().name()),
                result.snapshot().map(PersistedWeeklyReviewV3Snapshot::response).orElse(null));
    }
}
