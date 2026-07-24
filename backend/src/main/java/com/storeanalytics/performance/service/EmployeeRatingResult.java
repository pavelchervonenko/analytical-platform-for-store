package com.storeanalytics.performance.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EmployeeRatingResult(
        UUID storeId,
        LocalDate periodStart,
        LocalDate periodEnd,
        RatingFormulaView formula,
        RatingPlanContext plan,
        List<EmployeeRatingEntry> employees,
        EmployeeRatingHistoryView history
) {

    public EmployeeRatingResult withHistory(EmployeeRatingHistoryView newHistory) {
        return new EmployeeRatingResult(
                storeId,
                periodStart,
                periodEnd,
                formula,
                plan,
                employees,
                newHistory
        );
    }
}
