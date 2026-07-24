package com.storeanalytics.report.service;

import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReportSummaryView(
        UUID id,
        UUID storeId,
        ReportType type,
        LocalDate periodStart,
        LocalDate periodEnd,
        ReportCoverageStatus coverage,
        ReportStatus status,
        int revision,
        boolean currentRevision,
        UUID supersedesReportId,
        String revisionReason,
        UUID payrollRunId,
        String templateVersion,
        int schemaVersion,
        Instant finalizedAt,
        ReportActorView finalizedBy
) {
}
