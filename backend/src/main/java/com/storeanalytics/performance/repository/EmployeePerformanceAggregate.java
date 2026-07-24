package com.storeanalytics.performance.repository;

import java.math.BigDecimal;
import java.util.UUID;

public record EmployeePerformanceAggregate(
        UUID employeeId,
        String displayName,
        boolean employeeActive,
        boolean assignmentActive,
        boolean participatesInRanking,
        BigDecimal netRevenue,
        BigDecimal accessoryRevenue,
        BigDecimal serviceRevenue,
        BigDecimal additionalRevenue,
        long shiftCount,
        BigDecimal workedHours
) {
}
