package com.storeanalytics.metrics.service;

import java.math.BigDecimal;

public record CategoryKpiMetrics(
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        BigDecimal grossProfit,
        BigDecimal averageGrossProfitPerUnit,
        BigDecimal marginPercent,
        CategoryKpiDataQuality dataQuality
) {
}
