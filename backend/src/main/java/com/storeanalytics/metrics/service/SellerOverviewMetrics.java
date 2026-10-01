package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

/**
 * Internal Overview-only bundle. Full employee projections are exclusively for store reconciliation;
 * weekly consumers receive SellerPeriodMetrics and must never use these full-store projections.
 */
public record SellerOverviewMetrics(
        SellerPeriodMetrics sellers,
        EmployeeKpiResult reconciliationEmployees,
        EmployeeCategoryKpiResult reconciliationCategories
) {
    public SellerOverviewMetrics {
        requireNonNull(sellers, "sellers");
        requireNonNull(reconciliationEmployees, "reconciliationEmployees");
        requireNonNull(reconciliationCategories, "reconciliationCategories");
    }
}
