package com.storeanalytics.interpretation.review.ai;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public final class WeeklyReviewAiDisabledException extends RuntimeException {

    public WeeklyReviewAiDisabledException() {
        super("Weekly review AI generation is disabled");
    }
}
