package com.storeanalytics.interpretation.review.ai;

/** A stale worker must stop before crossing the billable provider boundary. */
final class WeeklyReviewAiLeaseLostException extends RuntimeException {

    WeeklyReviewAiLeaseLostException() {
        super("Weekly review AI lease no longer permits a provider attempt");
    }
}
