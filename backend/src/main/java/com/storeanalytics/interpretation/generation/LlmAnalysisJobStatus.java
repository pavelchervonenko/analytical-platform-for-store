package com.storeanalytics.interpretation.generation;

public enum LlmAnalysisJobStatus {
    PENDING,
    RUNNING,
    WAITING_RETRY,
    SUCCESS,
    VALIDATION_FAILED,
    FAILED,
    SKIPPED,
    CANCELLED
}
