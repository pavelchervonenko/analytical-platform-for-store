package com.storeanalytics.performance.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requirePositive;
import static com.storeanalytics.common.validation.ModelValidation.require;

import java.math.BigDecimal;

public record StorePlanTargets(
        BigDecimal revenue,
        BigDecimal accessorySharePercent,
        BigDecimal serviceSharePercent,
        BigDecimal additionalSharePercent
) {

    public StorePlanTargets {
        revenue = requirePositive(revenue, "revenue", 19, 2);
        accessorySharePercent = percentage(accessorySharePercent, "accessorySharePercent");
        serviceSharePercent = percentage(serviceSharePercent, "serviceSharePercent");
        additionalSharePercent = percentage(additionalSharePercent, "additionalSharePercent");
    }

    private static BigDecimal percentage(BigDecimal value, String fieldName) {
        BigDecimal normalized = requireNonNegative(value, fieldName, 5, 2);
        require(normalized.compareTo(BigDecimal.valueOf(100)) <= 0,
                fieldName + " must not exceed 100");
        return normalized;
    }
}
