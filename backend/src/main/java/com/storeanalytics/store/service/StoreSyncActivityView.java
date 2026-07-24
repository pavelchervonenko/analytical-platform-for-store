package com.storeanalytics.store.service;

import java.time.Instant;
import java.util.UUID;

public record StoreSyncActivityView(
        boolean active,
        UUID id,
        StoreSyncActivityType type,
        String status,
        String phase,
        Instant startedAt,
        Instant nextAttemptAt
) {
}
