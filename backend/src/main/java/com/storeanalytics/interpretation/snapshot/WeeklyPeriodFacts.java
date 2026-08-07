package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiResult;
import com.storeanalytics.metrics.service.EmployeeKpiResult;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingResult;

public record WeeklyPeriodFacts(
        StoreKpiResult store,
        CategoryKpiResult categories,
        AttachRateResult attachRates,
        EmployeeKpiResult employees,
        EmployeeCategoryKpiResult employeeCategories,
        EmployeeRatingResult employeeRatings,
        EmployeeSalesSampleFacts employeeSalesSamples
) {

    public WeeklyPeriodFacts {
        requireNonNull(store, "store");
        requireNonNull(categories, "categories");
        requireNonNull(attachRates, "attachRates");
        requireNonNull(employees, "employees");
        requireNonNull(employeeCategories, "employeeCategories");
        requireNonNull(employeeRatings, "employeeRatings");
        requireNonNull(employeeSalesSamples, "employeeSalesSamples");
    }
}
