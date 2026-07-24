package com.storeanalytics.metrics.service;

public record CategoryKpiDataQuality(
        boolean completeCostData,
        long includedItemCount,
        long missingCostItemCount,
        long unexpectedZeroCostItemCount
) {
}
