package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import com.storeanalytics.metrics.service.SellerPeriodAnalyticsService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.store.service.StoreDataStatusService;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Loads raw seller facts for a future v3 projection without changing the v2 generator. */
@Component
class SellerWeeklyReviewFactsSource {

    private final SellerPeriodAnalyticsService sellers;
    private final StoreDataStatusService dataStatus;
    private final SellerWeeklySourceStabilityRepository sourceStability;
    private final SellerWeeklySourceCoverageRepository sourceCoverage;
    private final SellerWeeklySourceRevisionRepository sourceRevision;
    private final WeeklyReviewPolicyV1 periodPolicy = new WeeklyReviewPolicyV1();

    SellerWeeklyReviewFactsSource(
            SellerPeriodAnalyticsService sellers, StoreDataStatusService dataStatus,
            SellerWeeklySourceStabilityRepository sourceStability,
            SellerWeeklySourceCoverageRepository sourceCoverage,
            SellerWeeklySourceRevisionRepository sourceRevision
    ) {
        this.sellers = sellers;
        this.dataStatus = dataStatus;
        this.sourceStability = sourceStability;
        this.sourceCoverage = sourceCoverage;
        this.sourceRevision = sourceRevision;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SellerWeeklyReviewFacts load(UUID storeId, Instant now, String timezone) {
        UUID selectedStore = requireNonNull(storeId, "storeId");
        PeriodContext period = periodPolicy.period(requireNonNull(now, "now"),
                requireNonNull(timezone, "timezone"));
        StoreKpiPeriod current = new StoreKpiPeriod(
                period.current().start(), period.current().end());
        StoreKpiPeriod previous = new StoreKpiPeriod(
                period.previous().start(), period.previous().end());
        StoreDataStatusView status = dataStatus.get(selectedStore);
        SellerPeriodComparisonFacts comparison = sellers.readComparison(
                selectedStore, current, previous);
        ZoneId zone = ZoneId.of(period.timezone());
        SellerWeeklySourceStability stability = sourceStability.read(selectedStore,
                previous.start().atStartOfDay(zone).toInstant(),
                current.end().plusDays(1).atStartOfDay(zone).toInstant());
        SellerWeeklySourceCoverage coverage = sourceCoverage.read(selectedStore, period);
        long revision = sourceRevision.read(selectedStore);
        return new SellerWeeklyReviewFacts(
                selectedStore, period, status, comparison, status.lastCompletedSyncAt(),
                stability, coverage, revision);
    }
}
