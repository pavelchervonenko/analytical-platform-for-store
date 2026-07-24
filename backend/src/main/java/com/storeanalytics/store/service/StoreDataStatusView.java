package com.storeanalytics.store.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StoreDataStatusView(
        UUID storeId,
        StoreDataFreshnessStatus status,
        LocalDate expectedThroughDate,
        LocalDate dataThroughDate,
        LocalDate salesDataThroughDate,
        LocalDate returnsDataThroughDate,
        Integer lagDays,
        Instant lastCompletedSyncAt,
        StoreSyncActivityView synchronization,
        long openQualityIssueCount,
        String lastError,
        Instant lastErrorAt,
        Instant checkedAt
) {
}
