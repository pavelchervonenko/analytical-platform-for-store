package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.model.ReportStatus;
import com.storeanalytics.metrics.model.ReportType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface ReportSummaryProjection {

    UUID getId();

    UUID getStoreId();

    ReportType getType();

    LocalDate getPeriodStart();

    LocalDate getPeriodEnd();

    ReportStatus getStatus();

    int getRevision();

    Boolean getCurrentRevision();

    UUID getSupersedesReportId();

    String getRevisionReason();

    UUID getPayrollRunId();

    String getTemplateVersion();

    int getSchemaVersion();

    Instant getFinalizedAt();

    UUID getFinalizedById();

    String getFinalizedByDisplayName();
}
