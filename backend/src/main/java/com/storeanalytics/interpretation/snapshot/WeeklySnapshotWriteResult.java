package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

public record WeeklySnapshotWriteResult(
        WeeklySnapshotWriteOutcome outcome,
        PersistedWeeklySnapshot snapshot
) {

    public WeeklySnapshotWriteResult {
        requireNonNull(outcome, "outcome");
        requireNonNull(snapshot, "snapshot");
    }
}
