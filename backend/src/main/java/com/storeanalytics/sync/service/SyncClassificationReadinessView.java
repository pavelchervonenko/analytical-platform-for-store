package com.storeanalytics.sync.service;

import java.time.Instant;

public record SyncClassificationReadinessView(
        String connectionKey,
        Instant periodStart,
        boolean ready,
        long effectiveAssignmentCount,
        long totalAssignmentCount,
        long productCount,
        long unmappedSalesItemCount
) {
}
