package com.storeanalytics.interpretation.snapshot;

public enum WeeklySnapshotJobStatus {
    PENDING,
    RUNNING,
    WAITING_RETRY,
    SUCCESS,
    FAILED,
    CANCELLED
}
