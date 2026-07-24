package com.storeanalytics.sync.service;

import com.storeanalytics.sync.model.SyncStatus;
import java.util.UUID;

public record ManualSyncAuditSummary(
        UUID syncRunId,
        SyncStatus status,
        int recordsFetched,
        int recordsCreated,
        int recordsUpdated,
        int recordsSkipped,
        int recordsFailed
) {
}
