package com.storeanalytics.report.service;

import java.util.List;

/** Manager-facing annual report projection with sanitized monthly snapshots. */
public record AnnualReportView(
        int schemaVersion,
        ReportHeader header,
        AnnualStoreTotals totals,
        List<AnnualCategoryTotals> categories,
        List<AnnualAttachRateTotals> attachRates,
        List<AnnualEmployeeTotals> employees,
        List<AnnualReportMonthView> months
) {
    public static AnnualReportView from(AnnualReportPayload source) {
        return new AnnualReportView(
                source.schemaVersion(),
                source.header(),
                source.totals(),
                source.categories(),
                source.attachRates(),
                source.employees(),
                source.months().stream().map(AnnualReportMonthView::from).toList()
        );
    }
}
