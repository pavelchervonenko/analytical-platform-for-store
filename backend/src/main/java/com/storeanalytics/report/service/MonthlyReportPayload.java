package com.storeanalytics.report.service;

import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.quality.service.StorePeriodQualityView;
import com.storeanalytics.salary.service.PayrollRunDetailView;

public record MonthlyReportPayload(
        int schemaVersion,
        ReportHeader header,
        StoreKpiResult storeKpi,
        CategoryKpiResult categoryKpi,
        ReportAverageKpi averageKpi,
        AttachRateResult attachRates,
        StorePlanProgressView planProgress,
        EmployeeRatingResult employeeRating,
        PayrollRunDetailView payroll,
        StorePeriodQualityView quality
) {
}
