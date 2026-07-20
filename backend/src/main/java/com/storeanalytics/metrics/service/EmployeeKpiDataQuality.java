package com.storeanalytics.metrics.service;

public record EmployeeKpiDataQuality(
        boolean completeCostData,
        long includedItemCount,
        long unmappedItemCount,
        long missingCostItemCount,
        long unexpectedZeroCostItemCount
) {
}
