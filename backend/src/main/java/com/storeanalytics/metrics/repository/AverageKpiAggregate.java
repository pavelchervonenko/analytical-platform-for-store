package com.storeanalytics.metrics.repository;

import java.math.BigDecimal;

public record AverageKpiAggregate(
        String categoryCode,
        String categoryName,
        boolean categoryActive,
        BigDecimal currentCategoryRevenue,
        BigDecimal currentCategoryQuantity,
        BigDecimal previousCategoryRevenue,
        BigDecimal previousCategoryQuantity,
        BigDecimal currentNetRevenue,
        long currentReceiptCount,
        BigDecimal currentAdditionalRevenue,
        BigDecimal currentPhoneQuantity,
        BigDecimal previousNetRevenue,
        long previousReceiptCount,
        BigDecimal previousAdditionalRevenue,
        BigDecimal previousPhoneQuantity
) {
}
