package com.storeanalytics.metrics.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.LocalDate;

public record ReportDefinition(
        String reportType,
        ReportPeriodType periodType,
        LocalDate periodStart,
        LocalDate periodEnd,
        ReportStatus status,
        String formulaVersion,
        String classificationVersion
) {

    public ReportDefinition {
        reportType = requireText(reportType, "reportType");
        periodType = requireNonNull(periodType, "periodType");
        periodStart = requireNonNull(periodStart, "periodStart");
        periodEnd = requireNonNull(periodEnd, "periodEnd");
        status = requireNonNull(status, "status");
        formulaVersion = requireText(formulaVersion, "formulaVersion");
        classificationVersion = requireText(classificationVersion, "classificationVersion");
        require(!periodEnd.isBefore(periodStart),
                "periodEnd must not be before periodStart");
    }
}
