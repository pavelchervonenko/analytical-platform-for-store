package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.require;

import java.math.BigDecimal;

public record PayrollSchemeDefinition(
        BigDecimal achievedPercentage,
        BigDecimal missedPercentage,
        BigDecimal achievedTier1Rate,
        BigDecimal missedTier1Rate,
        BigDecimal achievedTier2Rate,
        BigDecimal missedTier2Rate,
        BigDecimal advanceAmount
) {

    public PayrollSchemeDefinition {
        achievedPercentage = percentage(achievedPercentage, "achievedPercentage");
        missedPercentage = percentage(missedPercentage, "missedPercentage");
        achievedTier1Rate = money(achievedTier1Rate, "achievedTier1Rate");
        missedTier1Rate = money(missedTier1Rate, "missedTier1Rate");
        achievedTier2Rate = money(achievedTier2Rate, "achievedTier2Rate");
        missedTier2Rate = money(missedTier2Rate, "missedTier2Rate");
        advanceAmount = money(advanceAmount, "advanceAmount");
    }

    private static BigDecimal percentage(BigDecimal value, String fieldName) {
        BigDecimal normalized = requireNonNegative(value, fieldName, 5, 2);
        require(normalized.compareTo(BigDecimal.valueOf(100)) <= 0,
                fieldName + " must not exceed 100");
        return normalized;
    }

    private static BigDecimal money(BigDecimal value, String fieldName) {
        return requireNonNegative(value, fieldName, 19, 2);
    }
}
