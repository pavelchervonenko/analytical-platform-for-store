package com.storeanalytics.metrics.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record EmployeeCategoryKpiEmployee(
        UUID employeeId,
        String displayName,
        boolean employeeActive,
        boolean assignedToStore,
        boolean assignmentActive,
        boolean participatesInRanking,
        boolean rankingEligible,
        boolean unassigned,
        BigDecimal netRevenue,
        EmployeeKpiDataQuality dataQuality,
        List<EmployeeCategoryKpiGroup> groups,
        List<EmployeeCategoryKpiEntry> categories
) {
}
