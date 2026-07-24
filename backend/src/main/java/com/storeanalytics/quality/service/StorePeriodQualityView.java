package com.storeanalytics.quality.service;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StorePeriodQualityView(
        UUID storeId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodMonth,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodEnd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate asOfDate,
        DataQualityHealthStatus status,
        boolean readyForDecisions,
        List<PeriodQualityAreaView> areas,
        PeriodSourceDataQualityView sourceData,
        PeriodPlanQualityView storePlan,
        PeriodRatingQualityView employeeRating,
        PeriodPayrollQualityView payroll,
        List<PeriodQualityIssueView> issues,
        Instant checkedAt
) {
}
