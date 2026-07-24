package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireNumeric;
import static com.storeanalytics.common.validation.ModelValidation.requirePositive;

import java.math.BigDecimal;

public record PayrollPlanResult(
        BigDecimal revenueTarget,
        BigDecimal actualRevenue,
        boolean revenueAchieved,
        BigDecimal accessoryShareTarget,
        BigDecimal actualAccessoryTurnover,
        BigDecimal actualAccessorySharePercent,
        boolean accessoryAchieved,
        BigDecimal serviceShareTarget,
        BigDecimal actualServiceTurnover,
        BigDecimal actualServiceSharePercent,
        boolean serviceAchieved
) {

    public PayrollPlanResult {
        revenueTarget = requirePositive(revenueTarget, "revenueTarget", 19, 2);
        actualRevenue = money(actualRevenue, "actualRevenue");
        accessoryShareTarget = percentage(accessoryShareTarget, "accessoryShareTarget");
        actualAccessoryTurnover = money(actualAccessoryTurnover, "actualAccessoryTurnover");
        actualAccessorySharePercent = nullablePercentage(
                actualAccessorySharePercent, "actualAccessorySharePercent"
        );
        serviceShareTarget = percentage(serviceShareTarget, "serviceShareTarget");
        actualServiceTurnover = money(actualServiceTurnover, "actualServiceTurnover");
        actualServiceSharePercent = nullablePercentage(
                actualServiceSharePercent, "actualServiceSharePercent"
        );
    }

    public PayrollPlanStatus status() {
        return new PayrollPlanStatus(
                revenueAchieved, accessoryAchieved, serviceAchieved
        );
    }

    private static BigDecimal money(BigDecimal value, String fieldName) {
        return requireNumeric(value, fieldName, 19, 2);
    }

    private static BigDecimal percentage(BigDecimal value, String fieldName) {
        return requireNumeric(value, fieldName, 5, 2);
    }

    private static BigDecimal nullablePercentage(BigDecimal value, String fieldName) {
        return value == null
                ? null
                : requireNumeric(requireNonNull(value, fieldName), fieldName, 9, 2);
    }
}
