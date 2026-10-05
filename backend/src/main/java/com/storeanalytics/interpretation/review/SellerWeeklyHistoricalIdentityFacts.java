package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.UUID;

/** Metadata only, issued after scoped coverage/stability checks in the same repeatable-read transaction. */
record SellerWeeklyHistoricalIdentityFacts(UUID storeId, WeeklyReviewResponse.PeriodContext period,
        long sourceRevision, SellerWeeklyHistoricalMembership membership, String attachFormulaVersion) {
    SellerWeeklyHistoricalIdentityFacts {
        requireNonNull(storeId, "storeId");
        requireNonNull(period, "period");
        requireNonNull(membership, "membership");
        require(sourceRevision >= 0, "Negative source revision");
        require("attach-rate-v4-historical-membership-v1".equals(attachFormulaVersion),
                "Historical identity requires temporal attach policy");
    }
}
