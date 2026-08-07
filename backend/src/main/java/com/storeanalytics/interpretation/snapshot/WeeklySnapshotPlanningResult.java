package com.storeanalytics.interpretation.snapshot;

public record WeeklySnapshotPlanningResult(
        int storesScanned,
        int requestsAccepted,
        int alreadyPlanned,
        int sourceUnavailable,
        int revisionWindowClosed,
        int sourceUnchanged,
        int conflicts,
        int invalidStores
) {
}
