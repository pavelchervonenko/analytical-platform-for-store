package com.storeanalytics.maintenance;

public record RetentionBatchResult(long rollups, long deleted) {

    public RetentionBatchResult {
        if (rollups < 0 || deleted < 0) {
            throw new IllegalArgumentException("retention counts must not be negative");
        }
    }
}
