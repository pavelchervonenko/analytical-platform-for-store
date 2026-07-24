package com.storeanalytics.report.service;

import java.math.BigDecimal;

public record AnnualStoreTotals(
        int monthCount,
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        BigDecimal grossProfit,
        BigDecimal marginPercent,
        BigDecimal payrollEarnedAmount,
        BigDecimal payrollPayableAmount
) {
}
