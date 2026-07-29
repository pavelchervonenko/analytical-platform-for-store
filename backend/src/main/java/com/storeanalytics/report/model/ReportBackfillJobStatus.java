package com.storeanalytics.report.model;

public enum ReportBackfillJobStatus {
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
