package com.storeanalytics.interpretation.operations;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LlmJobIncidentView(
        UUID jobId,
        UUID snapshotId,
        UUID storeId,
        String storeName,
        LocalDate periodStart,
        LocalDate periodEnd,
        int snapshotRevision,
        int generationRevision,
        String triggerType,
        String status,
        String phase,
        int attemptCount,
        int transportRetryCount,
        int validationRetryCount,
        Instant nextAttemptAt,
        Instant deadlineAt,
        boolean cancelRequested,
        String terminalReasonCode,
        String errorSummary,
        String lastAttemptStatus,
        Integer lastHttpStatus,
        Instant updatedAt
) {
}
