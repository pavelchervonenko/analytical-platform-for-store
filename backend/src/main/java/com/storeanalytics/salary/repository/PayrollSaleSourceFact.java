package com.storeanalytics.salary.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollSaleSourceFact(
        UUID itemId,
        LocalDate payrollDate,
        int sign,
        BigDecimal quantity,
        BigDecimal netAmount,
        BigDecimal costAmount,
        UUID productId,
        UUID analyticsCategoryId,
        String basePayrollCategory,
        UUID overrideAssignmentId,
        String effectivePayrollCategory,
        LocalDate overrideValidFrom,
        LocalDate overrideValidTo,
        boolean excluded
) {
}
