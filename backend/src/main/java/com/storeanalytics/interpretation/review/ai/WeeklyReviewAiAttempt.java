package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Instant;
import java.util.UUID;

public record WeeklyReviewAiAttempt(
        UUID id,
        UUID jobId,
        int attemptNumber,
        Instant startedAt
) {

    public WeeklyReviewAiAttempt {
        requireNonNull(id, "id");
        requireNonNull(jobId, "jobId");
        require(attemptNumber >= 1 && attemptNumber <= 2,
                "attemptNumber must be 1 or 2");
        requireNonNull(startedAt, "startedAt");
    }
}
