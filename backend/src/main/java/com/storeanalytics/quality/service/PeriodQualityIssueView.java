package com.storeanalytics.quality.service;

import com.storeanalytics.quality.model.DataQualitySeverity;

public record PeriodQualityIssueView(
        String key,
        PeriodQualityAreaCode area,
        String code,
        DataQualitySeverity severity,
        String message,
        Long affectedCount,
        PeriodQualityAction recommendedAction
) {
}
