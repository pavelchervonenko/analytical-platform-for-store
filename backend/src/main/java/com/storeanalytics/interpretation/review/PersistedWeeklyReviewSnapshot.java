package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.util.UUID;

public record PersistedWeeklyReviewSnapshot(
        UUID id,
        UUID storeId,
        int revision,
        UUID supersedesSnapshotId,
        WeeklyReviewResponse response,
        String contentHash,
        Instant createdAt
) {

    public PersistedWeeklyReviewSnapshot {
        requireNonNull(id, "id");
        requireNonNull(storeId, "storeId");
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
        requireNonNull(response, "response");
        requireText(contentHash, "contentHash");
        requireNonNull(createdAt, "createdAt");
    }
}
