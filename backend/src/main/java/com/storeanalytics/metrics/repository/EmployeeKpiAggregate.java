package com.storeanalytics.metrics.repository;

import java.math.BigDecimal;
import java.util.UUID;

public record EmployeeKpiAggregate(
        UUID employeeId,
        String displayName,
        boolean employeeActive,
        boolean assignedToStore,
        boolean assignmentActive,
        boolean participatesInRanking,
        boolean unassigned,
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        long includedItemCount,
        long unmappedItemCount,
        long missingCostItemCount,
        long unexpectedZeroCostItemCount
) {
}
