package com.storeanalytics.report.service;

record EncodedReport(
        String payload,
        String sha256
) {
}
