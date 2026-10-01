package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.util.UUID;

/** Complete canonical identity input, without financial values, attach quantities or employee cards. */
record SellerWeeklyIdentityFacts(
        UUID storeId,
        PeriodContext period,
        StoreDataStatusView sourceDataStatus,
        String cohortFingerprint,
        String currentAttachFormulaVersion,
        String previousAttachFormulaVersion,
        SellerWeeklySourceStability sourceStability,
        SellerWeeklySourceCoverage sourceCoverage,
        long sourceRevision
) {

    SellerWeeklyIdentityFacts {
        requireNonNull(storeId, "storeId");
        requireNonNull(period, "period");
        requireNonNull(sourceDataStatus, "sourceDataStatus");
        requireNonNull(cohortFingerprint, "cohortFingerprint");
        requireNonNull(currentAttachFormulaVersion, "currentAttachFormulaVersion");
        requireNonNull(previousAttachFormulaVersion, "previousAttachFormulaVersion");
        requireNonNull(sourceStability, "sourceStability");
        requireNonNull(sourceCoverage, "sourceCoverage");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("Seller weekly source revision must not be negative");
        }
    }

    static SellerWeeklyIdentityFacts from(SellerWeeklyReviewFacts facts) {
        SellerWeeklyReviewFacts source = requireNonNull(facts, "facts");
        return new SellerWeeklyIdentityFacts(source.storeId(), source.period(), source.sourceDataStatus(),
                source.comparison().current().metrics().cohort().fingerprint(),
                source.comparison().current().attachFormulaVersion(),
                source.comparison().previous().attachFormulaVersion(), source.sourceStability(),
                source.sourceCoverage(), source.sourceRevision());
    }
}
