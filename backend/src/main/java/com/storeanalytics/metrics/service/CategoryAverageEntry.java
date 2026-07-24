package com.storeanalytics.metrics.service;

public record CategoryAverageEntry(
        String categoryCode,
        String categoryName,
        boolean categoryActive,
        AverageMetricComparison averageUnitPrice
) {
}
