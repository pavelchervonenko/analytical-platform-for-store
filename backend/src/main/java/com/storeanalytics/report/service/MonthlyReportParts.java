package com.storeanalytics.report.service;

import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.quality.service.StorePeriodQualityView;

record MonthlyReportParts(
        StoreKpiResult storeKpi,
        CategoryKpiResult categoryKpi,
        AverageKpiResult averageKpi,
        AttachRateResult attachRates,
        StorePlanProgressView planProgress,
        EmployeeRatingResult employeeRating,
        StorePeriodQualityView quality
) {
}
