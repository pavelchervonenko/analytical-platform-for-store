package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayrollPreviewDayView(
        LocalDate workDate,
        BigDecimal accessoryTurnover,
        BigDecimal serviceTurnover,
        BigDecimal playstationGrossProfit,
        BigDecimal paidRepairGrossProfit,
        BigDecimal tier1Quantity,
        BigDecimal tier2Quantity,
        BigDecimal accessoryReward,
        BigDecimal serviceReward,
        BigDecimal playstationReward,
        BigDecimal paidRepairReward,
        BigDecimal tier1Reward,
        BigDecimal tier2Reward,
        BigDecimal fundAmount,
        int shiftEmployeeCount,
        boolean calculationComplete,
        List<PayrollPreviewAllocationView> allocations
) {
}
