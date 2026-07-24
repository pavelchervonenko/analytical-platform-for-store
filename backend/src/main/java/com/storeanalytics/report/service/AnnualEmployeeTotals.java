package com.storeanalytics.report.service;

import java.math.BigDecimal;
import java.util.UUID;

public record AnnualEmployeeTotals(
        UUID employeeId,
        String employeeName,
        long shiftCount,
        BigDecimal workedHours,
        BigDecimal netRevenue,
        BigDecimal earnedAmount,
        BigDecimal payableAmount
) {
}
