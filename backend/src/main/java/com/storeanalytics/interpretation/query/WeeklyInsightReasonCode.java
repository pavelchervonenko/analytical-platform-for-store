package com.storeanalytics.interpretation.query;

public enum WeeklyInsightReasonCode {
    READY,
    WAITING_FOR_DATA,
    ANALYSIS_IN_PROGRESS,
    SOURCE_DELAYED,
    ANALYSIS_DELAYED,
    DATA_QUALITY_BLOCKED,
    ANALYSIS_TEMPORARILY_UNAVAILABLE,
    PERIOD_NOT_AVAILABLE
}
