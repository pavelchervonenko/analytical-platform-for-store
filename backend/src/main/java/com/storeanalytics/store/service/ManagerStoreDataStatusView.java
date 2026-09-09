package com.storeanalytics.store.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Store freshness information that is safe and actionable for a store manager.
 * Synchronization internals and data-quality diagnostics stay in administrator APIs.
 */
public record ManagerStoreDataStatusView(
        UUID storeId,
        StoreDataFreshnessStatus status,
        LocalDate expectedThroughDate,
        LocalDate dataThroughDate,
        Integer lagDays,
        boolean updating,
        Instant checkedAt
) {
    public static ManagerStoreDataStatusView from(StoreDataStatusView source) {
        return new ManagerStoreDataStatusView(
                source.storeId(),
                source.status(),
                source.expectedThroughDate(),
                source.dataThroughDate(),
                source.lagDays(),
                source.synchronization() != null && source.synchronization().active(),
                source.checkedAt()
        );
    }
}
