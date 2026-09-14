package com.storeanalytics.performance.service;

import java.time.LocalDate;
import java.util.UUID;

public record EmployeeCardView(
        UUID storeId,
        UUID employeeId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate previousPeriodStart,
        LocalDate previousPeriodEnd,
        RatingFormulaView formula,
        RatingPlanContext plan,
        EmployeeRatingEntry current,
        EmployeeRatingEntry previous,
        EmployeeRatingDynamics dynamics,
        EmployeePayrollContextView payroll
) {

    public EmployeeCardView withoutPayroll() {
        return new EmployeeCardView(
                storeId,
                employeeId,
                periodStart,
                periodEnd,
                previousPeriodStart,
                previousPeriodEnd,
                formula,
                plan,
                current,
                previous,
                dynamics,
                null
        );
    }
}
