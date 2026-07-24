package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requireNumeric;

import java.math.BigDecimal;

public record PayrollDailyPoolAmounts(
        BigDecimal accessoryPercentageRate,
        BigDecimal servicePercentageRate,
        BigDecimal tier1Rate,
        BigDecimal tier2Rate,
        BigDecimal accessoryReward,
        BigDecimal serviceReward,
        BigDecimal playstationReward,
        BigDecimal paidRepairReward,
        BigDecimal tier1Reward,
        BigDecimal tier2Reward,
        BigDecimal fundAmount
) {

    public PayrollDailyPoolAmounts {
        accessoryPercentageRate = percentage(
                accessoryPercentageRate, "accessoryPercentageRate"
        );
        servicePercentageRate = percentage(
                servicePercentageRate, "servicePercentageRate"
        );
        tier1Rate = requireNonNegative(tier1Rate, "tier1Rate", 19, 2);
        tier2Rate = requireNonNegative(tier2Rate, "tier2Rate", 19, 2);
        accessoryReward = money(accessoryReward, "accessoryReward");
        serviceReward = money(serviceReward, "serviceReward");
        playstationReward = nullableMoney(playstationReward, "playstationReward");
        paidRepairReward = nullableMoney(paidRepairReward, "paidRepairReward");
        tier1Reward = money(tier1Reward, "tier1Reward");
        tier2Reward = money(tier2Reward, "tier2Reward");
        fundAmount = nullableMoney(fundAmount, "fundAmount");
    }

    private static BigDecimal percentage(BigDecimal value, String fieldName) {
        return requireNonNegative(value, fieldName, 5, 2);
    }

    private static BigDecimal money(BigDecimal value, String fieldName) {
        return requireNumeric(value, fieldName, 19, 2);
    }

    private static BigDecimal nullableMoney(BigDecimal value, String fieldName) {
        return value == null ? null : money(value, fieldName);
    }
}
