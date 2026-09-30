package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeKpiAggregate;
import java.util.List;

/** Internal seller facts, not an Overview transport DTO or an AI-provider payload. */
public record SellerPeriodMetrics(
        SellerCohortSnapshot cohort,
        StoreKpiPeriod period,
        CategoryKpiMetrics totals,
        long unmappedItemCount,
        CategoryKpiResult categories,
        List<EmployeeKpiAggregate> employees,
        List<EmployeeCategoryKpiAggregate> employeeCategories
) {

    public SellerPeriodMetrics {
        requireNonNull(cohort, "cohort");
        requireNonNull(period, "period");
        requireNonNull(totals, "totals");
        requireNonNull(categories, "categories");
        employees = List.copyOf(employees);
        employeeCategories = List.copyOf(employeeCategories);
    }
}
