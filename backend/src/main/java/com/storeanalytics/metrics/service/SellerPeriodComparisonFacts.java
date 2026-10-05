package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

/** Both periods were read using one frozen selected cohort and one database snapshot. */
public record SellerPeriodComparisonFacts(SellerPeriodFacts current, SellerPeriodFacts previous) {

    public SellerPeriodComparisonFacts {
        requireNonNull(current, "current");
        requireNonNull(previous, "previous");
        if (!current.metrics().cohort().equals(previous.metrics().cohort())) {
            throw new IllegalArgumentException("Seller comparison must use one cohort");
        }
    }
}
