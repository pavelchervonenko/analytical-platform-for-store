package com.storeanalytics.report.service;

import java.util.UUID;

record AnnualReportSourceMonth(
        UUID snapshotId,
        int revision,
        String payloadHash
) {
}
