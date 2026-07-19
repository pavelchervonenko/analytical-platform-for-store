package com.storeanalytics.sync.exception;

import java.util.UUID;

public class ReturnSyncException extends RuntimeException {

    private final UUID syncRunId;

    public ReturnSyncException(UUID syncRunId, Throwable cause) {
        super("Return synchronization failed", cause);
        this.syncRunId = syncRunId;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }
}
