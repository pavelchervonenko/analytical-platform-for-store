package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Instant;

/** Canonical temporal selection and current action roster observed in the facts transaction. */
record SellerWeeklyHistoricalMembership(Instant authoritativeFrom, long revision,
        String selectionHash, String actionabilityHash) {
    static final String BASIS = "HISTORICAL_DOCUMENT_MEMBERSHIP_V1";

    SellerWeeklyHistoricalMembership {
        requireNonNull(authoritativeFrom, "authoritativeFrom");
        require(revision >= 0, "Negative membership revision");
        require(selectionHash != null && selectionHash.matches("[a-f0-9]{64}"), "Invalid selection hash");
        require(actionabilityHash != null && actionabilityHash.matches("[a-f0-9]{64}"), "Invalid actionability hash");
    }
}
