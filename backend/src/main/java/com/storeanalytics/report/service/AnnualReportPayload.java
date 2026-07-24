package com.storeanalytics.report.service;

import java.util.List;

public record AnnualReportPayload(
        int schemaVersion,
        ReportHeader header,
        AnnualStoreTotals totals,
        List<AnnualCategoryTotals> categories,
        List<AnnualAttachRateTotals> attachRates,
        List<AnnualEmployeeTotals> employees,
        List<AnnualReportMonthPayload> months
) {
}
