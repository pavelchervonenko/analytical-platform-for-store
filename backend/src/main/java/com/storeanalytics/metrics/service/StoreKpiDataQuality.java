package com.storeanalytics.metrics.service;

public record StoreKpiDataQuality(
        boolean completeCostData,
        long includedItemCount,
        long unmappedItemCount,
        long missingCostItemCount,
        long unexpectedZeroCostItemCount,
        long storeOpenQualityIssueCount
) {
}
