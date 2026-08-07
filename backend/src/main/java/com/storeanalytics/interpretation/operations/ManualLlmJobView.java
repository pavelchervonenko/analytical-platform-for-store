package com.storeanalytics.interpretation.operations;

import java.time.Instant;
import java.util.UUID;

public record ManualLlmJobView(
        UUID jobId,
        UUID snapshotId,
        int generationRevision,
        String status,
        String phase,
        boolean cancelRequested,
        Instant updatedAt
) {
}
