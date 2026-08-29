package com.storeanalytics.interpretation.review.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WeeklyReviewAiJobView(
        UUID jobId,
        UUID snapshotId,
        WeeklyReviewAiJobStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant deadlineAt,
        String lastErrorCode,
        List<String> lastValidationCodes
) {

    static WeeklyReviewAiJobView from(WeeklyReviewAiJob job) {
        return new WeeklyReviewAiJobView(
                job.id(),
                job.snapshotId(),
                job.status(),
                job.attemptCount(),
                job.maxAttempts(),
                job.nextAttemptAt(),
                job.deadlineAt(),
                job.lastErrorCode(),
                job.lastValidationCodes()
        );
    }
}
