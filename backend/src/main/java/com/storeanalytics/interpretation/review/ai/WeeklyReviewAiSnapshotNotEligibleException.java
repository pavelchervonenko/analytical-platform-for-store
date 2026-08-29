package com.storeanalytics.interpretation.review.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public final class WeeklyReviewAiSnapshotNotEligibleException
        extends RuntimeException {

    public WeeklyReviewAiSnapshotNotEligibleException() {
        super("Weekly review snapshot is not eligible for AI enrichment");
    }
}
