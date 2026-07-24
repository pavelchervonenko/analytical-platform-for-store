package com.storeanalytics.performance.service;

import java.time.Instant;
import java.util.UUID;

public record EmployeeRatingHistoryView(
        EmployeeRatingHistoryStatus status,
        UUID snapshotId,
        Instant finalizedAt,
        UUID finalizedBy,
        String finalizedByName
) {

    public static EmployeeRatingHistoryView live() {
        return new EmployeeRatingHistoryView(
                EmployeeRatingHistoryStatus.LIVE, null, null, null, null
        );
    }

    public static EmployeeRatingHistoryView finalized(
            UUID snapshotId,
            Instant finalizedAt,
            UUID finalizedBy,
            String finalizedByName
    ) {
        return new EmployeeRatingHistoryView(
                EmployeeRatingHistoryStatus.FINALIZED,
                snapshotId,
                finalizedAt,
                finalizedBy,
                finalizedByName
        );
    }
}
