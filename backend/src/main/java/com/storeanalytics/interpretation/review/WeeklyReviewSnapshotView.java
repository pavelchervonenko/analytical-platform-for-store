package com.storeanalytics.interpretation.review;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import java.time.Instant;
import java.util.UUID;

public record WeeklyReviewSnapshotView(
        UUID snapshotId,
        UUID storeId,
        int revision,
        UUID supersedesSnapshotId,
        DateRange period,
        ReportState reportState,
        String contentHash,
        Instant createdAt
) {

    public static WeeklyReviewSnapshotView from(
            PersistedWeeklyReviewSnapshot snapshot
    ) {
        return new WeeklyReviewSnapshotView(
                snapshot.id(),
                snapshot.storeId(),
                snapshot.revision(),
                snapshot.supersedesSnapshotId(),
                snapshot.response().period().current(),
                snapshot.response().reportState(),
                snapshot.contentHash(),
                snapshot.createdAt()
        );
    }
}
