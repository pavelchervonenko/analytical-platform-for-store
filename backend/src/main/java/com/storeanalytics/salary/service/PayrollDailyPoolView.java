package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollDailyPoolView(
        UUID id,
        LocalDate workDate,
        BigDecimal accessoryTurnover,
        BigDecimal serviceTurnover,
        BigDecimal playstationGrossProfit,
        BigDecimal paidRepairGrossProfit,
        BigDecimal tier1Quantity,
        BigDecimal tier2Quantity,
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
        BigDecimal fundAmount,
        int shiftEmployeeCount,
        int unmappedItemCount,
        int missingCostItemCount,
        boolean calculationComplete
) {
}
