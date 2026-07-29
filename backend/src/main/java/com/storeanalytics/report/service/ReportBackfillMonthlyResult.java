package com.storeanalytics.report.service;

record ReportBackfillMonthlyResult(
        boolean paidMonth,
        boolean created
) {

    static ReportBackfillMonthlyResult withoutPaidPayroll() {
        return new ReportBackfillMonthlyResult(false, false);
    }

    static ReportBackfillMonthlyResult existing() {
        return new ReportBackfillMonthlyResult(true, false);
    }

    static ReportBackfillMonthlyResult createdNew() {
        return new ReportBackfillMonthlyResult(true, true);
    }
}
