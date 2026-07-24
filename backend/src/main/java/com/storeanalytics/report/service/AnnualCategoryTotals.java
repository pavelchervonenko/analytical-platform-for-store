package com.storeanalytics.report.service;

import java.math.BigDecimal;

public record AnnualCategoryTotals(
        String categoryCode,
        String categoryName,
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        BigDecimal grossProfit,
        BigDecimal marginPercent
) {
}
