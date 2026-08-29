package com.storeanalytics.interpretation.review;

public record WeeklyReviewSnapshotPlanningResult(
        int storesScanned,
        int snapshotsCreated,
        int revisionsCreated,
        int contentReused,
        int sourceUnavailable,
        int sourceUnchanged,
        int invalidStores,
        int failures
) {
}
