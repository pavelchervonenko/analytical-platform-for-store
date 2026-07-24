package com.storeanalytics.quality.service;

public record PeriodQualityAreaView(
        PeriodQualityAreaCode code,
        DataQualityHealthStatus status,
        boolean ready,
        int issueCount,
        int errorCount,
        int warningCount,
        int infoCount
) {
}
