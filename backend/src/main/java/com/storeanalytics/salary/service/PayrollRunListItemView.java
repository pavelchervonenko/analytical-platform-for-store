package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollRunStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollRunListItemView(
        UUID id,
        UUID storeId,
        LocalDate periodMonth,
        int revision,
        UUID supersedesRunId,
        String revisionReason,
        PayrollRunStatus status,
        Instant createdAt
) {
}
