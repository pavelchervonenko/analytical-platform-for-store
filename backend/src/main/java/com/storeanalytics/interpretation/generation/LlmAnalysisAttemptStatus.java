package com.storeanalytics.interpretation.generation;

public enum LlmAnalysisAttemptStatus {
    STARTED,
    RESPONSE_RECEIVED,
    SUCCEEDED,
    TRANSIENT_FAILED,
    PERMANENT_FAILED,
    STRUCTURAL_INVALID,
    SEMANTIC_INVALID,
    UNKNOWN_OUTCOME,
    CANCELLED
}
