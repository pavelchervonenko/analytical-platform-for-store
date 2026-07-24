package com.storeanalytics.quality.service;

import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StoreDataQualitySummaryView(
        UUID storeId,
        String storeName,
        DataQualityHealthStatus status,
        StoreDataFreshnessStatus freshnessStatus,
        LocalDate dataThroughDate,
        Integer lagDays,
        int openIssueCount,
        int errorCount,
        int warningCount,
        int infoCount,
        Instant checkedAt
) {
}
