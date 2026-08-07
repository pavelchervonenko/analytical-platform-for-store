package com.storeanalytics.interpretation.snapshot;

public final class WeeklySnapshotIntegrityException extends IllegalStateException {

    public WeeklySnapshotIntegrityException(String message) {
        super(message);
    }

    public WeeklySnapshotIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
