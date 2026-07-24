package com.storeanalytics.performance.service;

import java.time.Instant;
import java.util.UUID;

public record EmployeeRatingSettingView(
        UUID employeeId,
        String displayName,
        boolean employeeActive,
        boolean assignmentActive,
        boolean participatesInRanking,
        long version,
        Instant updatedAt
) {
}
