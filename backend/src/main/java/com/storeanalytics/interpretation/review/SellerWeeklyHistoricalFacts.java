package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.metrics.service.SellerHistoricalComparisonFacts;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.Instant;
import java.util.UUID;

/** Distinct historical input: cannot accidentally enter the current-roster assembler. */
record SellerWeeklyHistoricalFacts(UUID storeId, PeriodContext period, StoreDataStatusView sourceDataStatus,
        SellerHistoricalComparisonFacts historical, Instant sourceDataUpdatedAt,
        SellerWeeklySourceStability sourceStability, SellerWeeklySourceCoverage sourceCoverage, long sourceRevision) {
    SellerWeeklyHistoricalFacts {
        requireNonNull(storeId, "storeId");
        requireNonNull(period, "period");
        requireNonNull(sourceDataStatus, "sourceDataStatus");
        requireNonNull(historical, "historical");
        requireNonNull(sourceStability, "sourceStability");
        requireNonNull(sourceCoverage, "sourceCoverage");
        require(sourceRevision >= 0, "Negative source revision");
        require(sourceCoverage.completeBothWeeks() && sourceStability == SellerWeeklySourceStability.STABLE,
                "Historical preparation requires reconciled coverage");
        var comparison = historical.comparison();
        require(storeId.equals(sourceDataStatus.storeId())
                && storeId.equals(comparison.current().metrics().cohort().storeId())
                && storeId.equals(comparison.previous().metrics().cohort().storeId())
                && period.current().start().equals(comparison.current().metrics().period().start())
                && period.current().end().equals(comparison.current().metrics().period().end())
                && period.previous().start().equals(comparison.previous().metrics().period().start())
                && period.previous().end().equals(comparison.previous().metrics().period().end()),
                "Historical facts do not match store or weeks");
    }
}
