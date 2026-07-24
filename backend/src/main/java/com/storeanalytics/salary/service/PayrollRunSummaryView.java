package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollPlanResult;
import com.storeanalytics.salary.model.PayrollRunStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollRunSummaryView(
        UUID id,
        UUID storeId,
        LocalDate periodMonth,
        int revision,
        UUID supersedesRunId,
        String revisionReason,
        PayrollRunStatus status,
        PayrollFreshnessView freshness,
        PayrollPlanResult planResult,
        boolean calculationComplete,
        int unmappedItemCount,
        int missingCostItemCount,
        int daysWithoutShift,
        UUID createdBy,
        UUID approvedBy,
        Instant approvedAt,
        UUID paidBy,
        Instant paidAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
