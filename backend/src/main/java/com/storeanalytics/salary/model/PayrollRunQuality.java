package com.storeanalytics.salary.model;

import static com.storeanalytics.common.validation.ModelValidation.require;

public record PayrollRunQuality(
        boolean complete,
        int unmappedItemCount,
        int missingCostItemCount,
        int daysWithoutShift
) {

    public PayrollRunQuality {
        require(unmappedItemCount >= 0, "unmappedItemCount must not be negative");
        require(missingCostItemCount >= 0, "missingCostItemCount must not be negative");
        require(daysWithoutShift >= 0, "daysWithoutShift must not be negative");
        require(complete == (unmappedItemCount == 0
                        && missingCostItemCount == 0
                        && daysWithoutShift == 0),
                "complete must match quality counters");
    }
}
