package com.storeanalytics.quality.service;

import io.swagger.v3.oas.annotations.media.Schema;

public record PeriodPlanQualityView(
        boolean planPresent,
        boolean inputDataCompleteThroughAsOf,
        boolean classificationComplete,
        long unmappedItemCount,
        long openQualityIssueCount,
        @Schema(
                nullable = true,
                description = "Plan formula version; null when no plan exists"
        ) String formulaVersion
) {
}
