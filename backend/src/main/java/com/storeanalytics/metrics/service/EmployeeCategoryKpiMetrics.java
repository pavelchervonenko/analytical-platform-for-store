package com.storeanalytics.metrics.service;

import java.math.BigDecimal;

public record EmployeeCategoryKpiMetrics(
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        BigDecimal grossProfit,
        BigDecimal marginPercent,
        BigDecimal revenueSharePercent,
        CategoryKpiDataQuality dataQuality
) {
}
