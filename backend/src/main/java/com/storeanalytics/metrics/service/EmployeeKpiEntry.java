package com.storeanalytics.metrics.service;

import java.math.BigDecimal;
import java.util.UUID;

public record EmployeeKpiEntry(
        UUID employeeId,
        String displayName,
        boolean employeeActive,
        boolean assignedToStore,
        boolean assignmentActive,
        boolean participatesInRanking,
        boolean rankingEligible,
        boolean unassigned,
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        BigDecimal grossProfit,
        BigDecimal marginPercent,
        EmployeeKpiDataQuality dataQuality
) {
}
