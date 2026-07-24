package com.storeanalytics.report.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReportHeader(
        UUID storeId,
        String storeName,
        String storeAddress,
        LocalDate reportingStartedOn,
        LocalDate periodStart,
        LocalDate periodEnd,
        ReportCoverageStatus coverage,
        String templateVersion,
        String dataContractVersion,
        Instant generatedAt,
        ReportActorView finalizedBy
) {
}
