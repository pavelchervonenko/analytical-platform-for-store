package com.storeanalytics.sync.model;

public enum SyncJobStatus {
    PENDING,
    RUNNING,
    WAITING_RETRY,
    SUCCESS,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
