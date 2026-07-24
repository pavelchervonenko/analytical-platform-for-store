package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PayrollEmployeeRevisionChange(
        UUID employeeId,
        String employeeName,
        BigDecimal previousEarnedAmount,
        BigDecimal currentEarnedAmount,
        BigDecimal earnedChange,
        BigDecimal previousPayableAmount,
        BigDecimal currentPayableAmount,
        BigDecimal payableChange,
        BigDecimal previousDeductionAmount,
        BigDecimal currentDeductionAmount,
        BigDecimal deductionChange,
        List<String> reasons
) {
}
