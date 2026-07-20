package com.storeanalytics.sync.service;

import com.storeanalytics.sync.model.SyncJobPhase;
import com.storeanalytics.sync.model.SyncJobType;
import java.time.Instant;
import java.util.UUID;

public record SyncJobClaim(
        UUID jobId,
        UUID requestedById,
        SyncJobType jobType,
        SyncJobPhase phase,
        Instant windowStart,
        Instant windowEnd,
        int attemptCount
) {
}
