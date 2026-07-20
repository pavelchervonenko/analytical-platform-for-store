package com.storeanalytics.metrics.repository;

import java.math.BigDecimal;

public record StoreKpiAggregate(
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        long includedItemCount,
        long unmappedItemCount,
        long missingCostItemCount,
        long unexpectedZeroCostItemCount,
        long storeOpenQualityIssueCount
) {
}
