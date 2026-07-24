package com.storeanalytics.salary.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollDailySalesAggregate(
        LocalDate workDate,
        BigDecimal netRevenue,
        BigDecimal accessoryTurnover,
        BigDecimal serviceTurnover,
        BigDecimal playstationGrossProfit,
        BigDecimal paidRepairGrossProfit,
        BigDecimal tier1Quantity,
        BigDecimal tier2Quantity,
        int unmappedItemCount,
        int missingCostItemCount
) {
}
