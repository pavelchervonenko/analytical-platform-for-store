package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PersistedWeeklySnapshot(
        UUID id,
        UUID storeId,
        WeeklyAnalyticsFactsQuery query,
        String timezone,
        int revision,
        UUID supersedesSnapshotId,
        String revisionReasonCode,
        String revisionNote,
        UUID sourceSyncJobId,
        Instant sourceSyncCompletedAt,
        Instant sourceDataCutoff,
        QualityStatus qualityStatus,
        Versions versions,
        WeeklySnapshotPayload payload,
        String factsHash,
        List<SnapshotEmployeeMembership> employees,
        Instant createdAt
) {

    public PersistedWeeklySnapshot {
        requireNonNull(id, "id");
        requireNonNull(storeId, "storeId");
        requireNonNull(query, "query");
        requireNonNull(qualityStatus, "qualityStatus");
        requireNonNull(versions, "versions");
        requireNonNull(payload, "payload");
        employees = List.copyOf(requireNonNull(employees, "employees"));
        requireNonNull(createdAt, "createdAt");
    }
}
