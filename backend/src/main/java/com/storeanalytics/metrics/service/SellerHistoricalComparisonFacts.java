package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.Set;
import java.util.UUID;

/** Temporal comparison and present-day actionability are distinct, not the current-roster contract. */
public record SellerHistoricalComparisonFacts(SellerPeriodComparisonFacts comparison, Set<UUID> actionEmployeeIds) {
    public SellerHistoricalComparisonFacts {
        requireNonNull(comparison, "comparison");
        actionEmployeeIds = Set.copyOf(requireNonNull(actionEmployeeIds, "actionEmployeeIds"));
        if (!comparison.current().metrics().cohort().employeeIds().containsAll(actionEmployeeIds)) {
            throw new IllegalArgumentException("Action IDs must belong to the historical cohort");
        }
    }
}
