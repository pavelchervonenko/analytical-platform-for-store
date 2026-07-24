package com.storeanalytics.report.service;

import java.util.UUID;

public record ReportBackfillResult(
        UUID storeId,
        int year,
        int paidMonthCount,
        int monthlyCreatedCount,
        int monthlyExistingCount,
        UUID annualReportId
) {
}
