package com.storeanalytics.report.service;

public record ReportDetailView(
        ReportSummaryView report,
        MonthlyReportView monthly,
        AnnualReportView annual
) {
}
