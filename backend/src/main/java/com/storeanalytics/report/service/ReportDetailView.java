package com.storeanalytics.report.service;

public record ReportDetailView(
        ReportSummaryView report,
        MonthlyReportPayload monthly,
        AnnualReportPayload annual
) {
}
