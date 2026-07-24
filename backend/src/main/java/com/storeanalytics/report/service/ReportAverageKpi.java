package com.storeanalytics.report.service;

import com.storeanalytics.metrics.service.AverageMetricSnapshot;
import java.util.List;

public record ReportAverageKpi(
        String formulaVersion,
        AverageMetricSnapshot averageReceipt,
        AverageMetricSnapshot additionalRevenuePerPhone,
        List<ReportCategoryAverage> categoryAveragePrices
) {
}
