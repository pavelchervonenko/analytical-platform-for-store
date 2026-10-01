package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.Instant;
import java.util.UUID;

/** Version 3 input only: both weeks use one selected seller cohort and one database snapshot. */
record SellerWeeklyReviewFacts(
        UUID storeId,
        PeriodContext period,
        StoreDataStatusView sourceDataStatus,
        SellerPeriodComparisonFacts comparison,
        Instant sourceDataUpdatedAt,
        SellerWeeklySourceStability sourceStability,
        SellerWeeklySourceCoverage sourceCoverage,
        long sourceRevision
) {

    SellerWeeklyReviewFacts {
        requireNonNull(storeId, "storeId");
        requireNonNull(period, "period");
        requireNonNull(sourceDataStatus, "sourceDataStatus");
        requireNonNull(comparison, "comparison");
        requireNonNull(sourceStability, "sourceStability");
        requireNonNull(sourceCoverage, "sourceCoverage");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("Seller weekly source revision must not be negative");
        }
        if (!storeId.equals(sourceDataStatus.storeId())
                || !storeId.equals(comparison.current().metrics().cohort().storeId())
                || !period.current().start().equals(comparison.current().metrics().period().start())
                || !period.current().end().equals(comparison.current().metrics().period().end())
                || !period.previous().start().equals(comparison.previous().metrics().period().start())
                || !period.previous().end().equals(comparison.previous().metrics().period().end())) {
            throw new IllegalArgumentException("Seller weekly facts source does not match store or weeks");
        }
    }
}
