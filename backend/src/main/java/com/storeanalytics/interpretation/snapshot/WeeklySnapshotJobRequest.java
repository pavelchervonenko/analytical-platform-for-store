package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.Instant;
import java.util.UUID;

public record WeeklySnapshotJobRequest(
        UUID storeId,
        UUID requestedBy,
        WeeklySnapshotJobType jobType,
        StoreKpiPeriod period,
        String timezone,
        UUID sourceSyncJobId,
        Instant sourceDataCutoff,
        Versions versions,
        UUID baseSnapshotId,
        int maxAttempts
) {

    public WeeklySnapshotJobRequest {
        requireNonNull(storeId, "storeId");
        requireNonNull(jobType, "jobType");
        requireNonNull(period, "period");
        timezone = requireText(timezone, "timezone");
        requireNonNull(sourceSyncJobId, "sourceSyncJobId");
        requireNonNull(sourceDataCutoff, "sourceDataCutoff");
        requireNonNull(versions, "versions");
        require(maxAttempts >= 1 && maxAttempts <= 20,
                "maxAttempts must be between 1 and 20");
        require(jobType != WeeklySnapshotJobType.AUTO_REVISION || baseSnapshotId != null,
                "AUTO_REVISION requires baseSnapshotId");
        require(jobType != WeeklySnapshotJobType.INITIAL || baseSnapshotId == null,
                "INITIAL must not have baseSnapshotId");
    }
}
