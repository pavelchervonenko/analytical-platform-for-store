package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.util.UUID;

/** Version-specific persisted view; legacy callers cannot accidentally receive a v3 payload. */
public record PersistedWeeklyReviewV3Snapshot(
        UUID id,
        UUID storeId,
        int revision,
        UUID supersedesSnapshotId,
        WeeklyReviewV3Response response,
        String contentHash,
        Instant createdAt
) implements PersistedWeeklyReview {
    public PersistedWeeklyReviewV3Snapshot {
        requireNonNull(id, "id");
        requireNonNull(storeId, "storeId");
        require(revision > 0, "revision must be positive");
        requireNonNull(response, "response");
        requireText(contentHash, "contentHash");
        requireNonNull(createdAt, "createdAt");
    }
}
