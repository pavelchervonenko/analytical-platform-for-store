package com.storeanalytics.report.service;

import java.util.UUID;

public record AnnualReportMonthPayload(
        UUID snapshotId,
        int revision,
        String payloadHash,
        MonthlyReportPayload report
) {
}
