package com.storeanalytics.interpretation.review.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public final class WeeklyReviewAiJobNotFoundException extends RuntimeException {

    public WeeklyReviewAiJobNotFoundException() {
        super("Weekly review AI job does not exist");
    }
}
