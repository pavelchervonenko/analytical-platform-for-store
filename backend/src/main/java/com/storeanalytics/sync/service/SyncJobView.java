package com.storeanalytics.sync.service;

import com.storeanalytics.sync.model.SyncJob;
import com.storeanalytics.sync.model.SyncJobPhase;
import com.storeanalytics.sync.model.SyncJobStatus;
import com.storeanalytics.sync.model.SyncJobType;
import java.time.Instant;
import java.util.UUID;

public record SyncJobView(
        UUID id,
        UUID connectionId,
        UUID requestedById,
        SyncJobType jobType,
        SyncJobStatus status,
        SyncJobPhase phase,
        Instant periodStart,
        Instant periodEnd,
        Instant cursorStart,
        Instant currentWindowEnd,
        int windowSizeMinutes,
        int attemptCount,
        int maxAttempts,
        int completedSteps,
        int totalRetries,
        boolean cancelRequested,
        Instant nextAttemptAt,
        Instant leaseUntil,
        String errorSummary,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {

    static SyncJobView from(SyncJob job) {
        return new SyncJobView(
                job.getId(),
                job.getConnection().getId(),
                job.getRequestedBy() == null ? null : job.getRequestedBy().getId(),
                job.getJobType(),
                job.getStatus(),
                job.getPhase(),
                job.getPeriodStart(),
                job.getPeriodEnd(),
                job.getCursorStart(),
                job.getCurrentWindowEnd(),
                job.getWindowSizeMinutes(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getCompletedSteps(),
                job.getTotalRetries(),
                job.isCancelRequested(),
                job.getNextAttemptAt(),
                job.getLeaseUntil(),
                job.getErrorSummary(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
