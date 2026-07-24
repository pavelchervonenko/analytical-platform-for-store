package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.util.UUID;

public record PayrollStatementView(
        UUID id,
        UUID employeeId,
        String employeeName,
        int shiftCount,
        BigDecimal workedHours,
        BigDecimal earnedAmount,
        BigDecimal advanceAmount,
        BigDecimal penaltyAmount,
        BigDecimal inventoryAmount,
        BigDecimal taxAmount,
        BigDecimal payableAmount
) {
}
