package com.storeanalytics.report.service;

import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.performance.service.StorePlanProgressView;
import com.storeanalytics.salary.service.PayrollRunDetailView;

/** Manager-facing report projection without administrator-only quality diagnostics. */
public record MonthlyReportView(
        int schemaVersion,
        ReportHeader header,
        StoreKpiResult storeKpi,
        CategoryKpiResult categoryKpi,
        ReportAverageKpi averageKpi,
        AttachRateResult attachRates,
        StorePlanProgressView planProgress,
        EmployeeRatingResult employeeRating,
        PayrollRunDetailView payroll
) {
    public static MonthlyReportView from(MonthlyReportPayload source) {
        return new MonthlyReportView(
                source.schemaVersion(),
                source.header(),
                source.storeKpi(),
                source.categoryKpi(),
                source.averageKpi(),
                source.attachRates(),
                source.planProgress(),
                source.employeeRating(),
                source.payroll()
        );
    }
}
