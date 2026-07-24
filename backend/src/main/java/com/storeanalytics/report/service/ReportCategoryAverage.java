package com.storeanalytics.report.service;

import com.storeanalytics.metrics.service.AverageMetricSnapshot;

public record ReportCategoryAverage(
        String categoryCode,
        String categoryName,
        boolean categoryActive,
        AverageMetricSnapshot averageUnitPrice
) {
}
