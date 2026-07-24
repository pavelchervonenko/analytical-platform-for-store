package com.storeanalytics.store.repository;

import java.time.Instant;
import java.util.UUID;

public record StoreDataStatusSnapshot(
        UUID storeId,
        String timezone,
        Instant salesThroughExclusive,
        Instant salesCompletedAt,
        Instant returnsThroughExclusive,
        Instant returnsCompletedAt,
        UUID activeSyncId,
        String activeSyncType,
        String activeSyncStatus,
        String activeSyncPhase,
        Instant activeSyncStartedAt,
        Instant activeSyncNextAttemptAt,
        String latestTerminalStatus,
        Instant latestTerminalAt,
        String lastError,
        Instant lastErrorAt,
        long openQualityIssueCount
) {
}
