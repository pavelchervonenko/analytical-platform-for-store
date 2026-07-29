package com.storeanalytics.maintenance;

public record SyncRunPurgeResult(long runs, long errors) {

    public SyncRunPurgeResult {
        if (runs < 0 || errors < 0) {
            throw new IllegalArgumentException("purge counts must not be negative");
        }
    }
}
