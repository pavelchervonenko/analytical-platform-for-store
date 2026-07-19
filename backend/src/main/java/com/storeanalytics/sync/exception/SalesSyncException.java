package com.storeanalytics.sync.exception;

import java.util.UUID;

public class SalesSyncException extends RuntimeException {

    private final UUID syncRunId;

    public SalesSyncException(UUID syncRunId, Throwable cause) {
        super("Sales synchronization failed", cause);
        this.syncRunId = syncRunId;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }
}
