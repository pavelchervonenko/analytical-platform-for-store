package com.storeanalytics.sync.model;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import java.time.Duration;
import java.time.Instant;

public record SyncJobDefinition(
        IntegrationConnection connection,
        AppUser requestedBy,
        SyncJobType jobType,
        Instant periodStart,
        Instant periodEnd,
        Duration windowSize,
        int maxAttempts
) {
}
