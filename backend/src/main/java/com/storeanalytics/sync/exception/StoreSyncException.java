package com.storeanalytics.sync.exception;

import java.util.UUID;

public class StoreSyncException extends RuntimeException {

    private final UUID syncRunId;

    public StoreSyncException(UUID syncRunId, Throwable cause) {
        super("Store synchronization failed", cause);
        this.syncRunId = syncRunId;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }
}
