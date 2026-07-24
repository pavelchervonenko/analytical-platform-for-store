package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requireNumeric;

import java.math.BigDecimal;

public record PayrollStatementAmounts(
        BigDecimal earnedAmount,
        BigDecimal advanceAmount,
        BigDecimal penaltyAmount,
        BigDecimal inventoryAmount,
        BigDecimal taxAmount,
        BigDecimal payableAmount
) {

    public PayrollStatementAmounts {
        earnedAmount = money(earnedAmount, "earnedAmount");
        advanceAmount = nonNegative(advanceAmount, "advanceAmount");
        penaltyAmount = nonNegative(penaltyAmount, "penaltyAmount");
        inventoryAmount = nonNegative(inventoryAmount, "inventoryAmount");
        taxAmount = nonNegative(taxAmount, "taxAmount");
        payableAmount = money(payableAmount, "payableAmount");
    }

    private static BigDecimal money(BigDecimal value, String fieldName) {
        return requireNumeric(value, fieldName, 19, 2);
    }

    private static BigDecimal nonNegative(BigDecimal value, String fieldName) {
        return requireNonNegative(value, fieldName, 19, 2);
    }
}
