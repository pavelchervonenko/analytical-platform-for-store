package com.storeanalytics.quality.service;

import com.storeanalytics.quality.model.DataQualitySeverity;
import java.time.Instant;

public record DataQualityIssueView(
        String key,
        DataQualitySource source,
        String code,
        DataQualitySeverity severity,
        String entityType,
        String message,
        Instant detectedAt,
        DataQualityRecommendedAction recommendedAction
) {
}
