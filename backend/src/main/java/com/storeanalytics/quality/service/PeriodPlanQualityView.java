package com.storeanalytics.quality.service;

public record PeriodPlanQualityView(
        boolean planPresent,
        boolean inputDataCompleteThroughAsOf,
        boolean classificationComplete,
        long unmappedItemCount,
        long openQualityIssueCount,
        String formulaVersion
) {
}
