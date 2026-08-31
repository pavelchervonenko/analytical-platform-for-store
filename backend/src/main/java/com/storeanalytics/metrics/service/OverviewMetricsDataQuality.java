package com.storeanalytics.metrics.service;

public record OverviewMetricsDataQuality(
        boolean completeCostData,
        long includedItemCount,
        long unmappedItemCount,
        long missingCostItemCount,
        long unexpectedZeroCostItemCount,
        long periodOpenConsistencyIssueCount,
        long storeOpenQualityIssueCount,
        boolean reconciliationPassed
) {
}
