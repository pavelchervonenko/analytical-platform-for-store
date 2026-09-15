package com.storeanalytics.report.service;

import java.util.UUID;

public record AnnualReportMonthView(
        UUID snapshotId,
        int revision,
        String payloadHash,
        MonthlyReportView report
) {
    public static AnnualReportMonthView from(AnnualReportMonthPayload source) {
        return new AnnualReportMonthView(
                source.snapshotId(),
                source.revision(),
                source.payloadHash(),
                MonthlyReportView.from(source.report())
        );
    }
}
