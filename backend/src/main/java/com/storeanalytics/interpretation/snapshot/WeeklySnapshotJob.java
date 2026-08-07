package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.Instant;
import java.util.UUID;

public record WeeklySnapshotJob(
        UUID id,
        UUID storeId,
        UUID requestedBy,
        WeeklySnapshotJobType jobType,
        StoreKpiPeriod period,
        String timezone,
        UUID sourceSyncJobId,
        Instant sourceDataCutoff,
        Versions versions,
        UUID baseSnapshotId,
        WeeklySnapshotJobStatus status,
        WeeklySnapshotWriteOutcome outcome,
        UUID resultSnapshotId,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        boolean cancelRequested,
        String errorCode,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
