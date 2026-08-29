package com.storeanalytics.interpretation.review.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public final class WeeklyReviewAiSnapshotNotFoundException extends RuntimeException {

    public WeeklyReviewAiSnapshotNotFoundException() {
        super("Weekly review snapshot does not exist");
    }
}
