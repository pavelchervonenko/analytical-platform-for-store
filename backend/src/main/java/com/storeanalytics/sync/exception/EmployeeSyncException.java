package com.storeanalytics.sync.exception;

import java.util.UUID;

public class EmployeeSyncException extends RuntimeException {

    private final UUID syncRunId;

    public EmployeeSyncException(UUID syncRunId, Throwable cause) {
        super("Employee synchronization failed", cause);
        this.syncRunId = syncRunId;
    }

    public UUID getSyncRunId() {
        return syncRunId;
    }
}
