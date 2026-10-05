package com.storeanalytics.interpretation.review.ai;

/** No paid attempt was started; the exact approved snapshot is no longer eligible. */
public final class WeeklyReviewAiSnapshotNotCurrentException extends RuntimeException {
    public WeeklyReviewAiSnapshotNotCurrentException() {
        super("Exact seller weekly snapshot is not current");
    }
}
