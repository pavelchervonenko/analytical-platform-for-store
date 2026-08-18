package com.storeanalytics.sync.exception;

import java.util.UUID;

public class OrderSyncException extends RuntimeException {

    private final UUID syncRunId;

    public OrderSyncException(UUID syncRunId, Throwable cause) {
        super("Order synchronization failed", cause);
        this.syncRunId = syncRunId;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }
}
