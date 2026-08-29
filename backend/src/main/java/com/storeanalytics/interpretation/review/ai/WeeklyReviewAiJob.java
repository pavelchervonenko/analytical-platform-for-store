package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WeeklyReviewAiJob(
        UUID id,
        UUID snapshotId,
        String promptVersion,
        int contentSchemaVersion,
        String providerCode,
        String requestedModel,
        WeeklyReviewAiJobStatus status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        Instant deadlineAt,
        String leaseOwner,
        Instant leaseUntil,
        String lastErrorCode,
        String lastErrorMessage,
        List<String> lastValidationCodes,
        Instant createdAt,
        Instant updatedAt
) {

    public WeeklyReviewAiJob {
        requireNonNull(id, "id");
        requireNonNull(snapshotId, "snapshotId");
        requireText(promptVersion, "promptVersion");
        require(contentSchemaVersion > 0, "contentSchemaVersion must be positive");
        requireText(providerCode, "providerCode");
        requireText(requestedModel, "requestedModel");
        requireNonNull(status, "status");
        require(attemptCount >= 0 && attemptCount <= maxAttempts,
                "attemptCount is outside maxAttempts");
        require(maxAttempts >= 1 && maxAttempts <= 2,
                "maxAttempts must be 1 or 2");
        requireNonNull(nextAttemptAt, "nextAttemptAt");
        requireNonNull(deadlineAt, "deadlineAt");
        lastValidationCodes = List.copyOf(requireNonNull(
                lastValidationCodes, "lastValidationCodes"
        ));
        requireNonNull(createdAt, "createdAt");
        requireNonNull(updatedAt, "updatedAt");
        require((status == WeeklyReviewAiJobStatus.RUNNING)
                        == (leaseOwner != null && leaseUntil != null),
                "lease fields do not match job status");
    }
}
