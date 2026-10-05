package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.require;

/** Store-wide unresolved return authors; never assigned to the original seller as a fallback. */
public record SellerReturnAttributionQuality(
        long missingReturnEmployeeCount,
        long unresolvedReturnEmployeeCount
) {
    public static final SellerReturnAttributionQuality COMPLETE = new SellerReturnAttributionQuality(0, 0);

    public SellerReturnAttributionQuality {
        require(missingReturnEmployeeCount >= 0, "missing return employee count must not be negative");
        require(unresolvedReturnEmployeeCount >= 0,
                "unresolved return employee count must not be negative");
    }

    public boolean complete() {
        return missingReturnEmployeeCount == 0 && unresolvedReturnEmployeeCount == 0;
    }
}
