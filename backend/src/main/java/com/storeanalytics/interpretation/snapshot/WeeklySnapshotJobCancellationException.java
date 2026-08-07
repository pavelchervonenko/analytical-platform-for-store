package com.storeanalytics.interpretation.snapshot;

public final class WeeklySnapshotJobCancellationException extends RuntimeException {

    public WeeklySnapshotJobCancellationException() {
        super("Weekly snapshot job cancellation was requested");
    }
}
