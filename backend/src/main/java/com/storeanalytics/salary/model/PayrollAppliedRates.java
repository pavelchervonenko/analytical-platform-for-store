package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;

import java.math.BigDecimal;

public record PayrollAppliedRates(
        BigDecimal accessoryPercentage,
        BigDecimal servicePercentage,
        BigDecimal tier1Rate,
        BigDecimal tier2Rate
) {

    public PayrollAppliedRates {
        accessoryPercentage = percentage(accessoryPercentage, "accessoryPercentage");
        servicePercentage = percentage(servicePercentage, "servicePercentage");
        tier1Rate = money(tier1Rate, "tier1Rate");
        tier2Rate = money(tier2Rate, "tier2Rate");
    }

    private static BigDecimal percentage(BigDecimal value, String fieldName) {
        return requireNonNegative(value, fieldName, 5, 2);
    }

    private static BigDecimal money(BigDecimal value, String fieldName) {
        return requireNonNegative(value, fieldName, 19, 2);
    }
}
