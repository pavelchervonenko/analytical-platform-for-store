package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayrollRevisionComparisonView(
        UUID storeId,
        LocalDate periodMonth,
        PayrollRunSummaryView previousRun,
        PayrollRunSummaryView currentRun,
        boolean revenuePlanStatusChanged,
        boolean accessoryPlanStatusChanged,
        boolean servicePlanStatusChanged,
        boolean schemeChanged,
        BigDecimal previousTotalFund,
        BigDecimal currentTotalFund,
        BigDecimal totalFundChange,
        BigDecimal previousTotalPayable,
        BigDecimal currentTotalPayable,
        BigDecimal totalPayableChange,
        List<PayrollEmployeeRevisionChange> employeeChanges,
        List<PayrollDayRevisionChange> dayChanges
) {
}
