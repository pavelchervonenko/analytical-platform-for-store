package com.storeanalytics.maintenance;

import java.util.Map;
import java.util.UUID;

public record DataRetentionRunResult(
        UUID runId,
        boolean lockAcquired,
        boolean dryRun,
        Map<String, Long> candidates,
        Map<String, Long> affected,
        Map<String, Long> remainingCandidates
) {

    public DataRetentionRunResult {
        if (runId == null || candidates == null || affected == null
                || remainingCandidates == null) {
            throw new IllegalArgumentException("retention result fields are required");
        }
        candidates = Map.copyOf(candidates);
        affected = Map.copyOf(affected);
        remainingCandidates = Map.copyOf(remainingCandidates);
    }

    public static DataRetentionRunResult skipped(UUID runId, boolean dryRun) {
        return new DataRetentionRunResult(
                runId,
                false,
                dryRun,
                Map.of(),
                Map.of(),
                Map.of()
        );
    }
}
