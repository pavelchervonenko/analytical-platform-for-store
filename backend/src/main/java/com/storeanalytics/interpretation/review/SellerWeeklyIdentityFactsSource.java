package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.metrics.repository.SellerAttachRateRepository;
import com.storeanalytics.metrics.repository.SellerCohortRepository;
import com.storeanalytics.store.service.StoreDataStatusService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Cheap internal CURRENT assessment; reads every identity field but never computes financial facts. */
@Component
class SellerWeeklyIdentityFactsSource {

    private final SellerCohortRepository cohorts;
    private final SellerAttachRateRepository attach;
    private final StoreDataStatusService dataStatus;
    private final SellerWeeklySourceStabilityRepository stability;
    private final SellerWeeklySourceCoverageRepository coverage;
    private final SellerWeeklySourceRevisionRepository revisions;
    private final WeeklyReviewPolicyV1 periodPolicy = new WeeklyReviewPolicyV1();

    SellerWeeklyIdentityFactsSource(SellerCohortRepository cohorts, SellerAttachRateRepository attach,
                                   StoreDataStatusService dataStatus,
                                   SellerWeeklySourceStabilityRepository stability,
                                   SellerWeeklySourceCoverageRepository coverage,
                                   SellerWeeklySourceRevisionRepository revisions) {
        this.cohorts = cohorts;
        this.attach = attach;
        this.dataStatus = dataStatus;
        this.stability = stability;
        this.coverage = coverage;
        this.revisions = revisions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SellerWeeklyIdentityFacts load(UUID storeId, Instant now, String timezone) {
        UUID selectedStore = requireNonNull(storeId, "storeId");
        PeriodContext period = periodPolicy.period(requireNonNull(now, "now"),
                requireNonNull(timezone, "timezone"));
        return read(selectedStore, period);
    }

    private SellerWeeklyIdentityFacts read(UUID storeId, PeriodContext period) {
        ZoneId zone = ZoneId.of(period.timezone());
        String formulaVersion = attach.formulaVersion();
        return new SellerWeeklyIdentityFacts(storeId, period, dataStatus.get(storeId),
                cohorts.read(storeId).fingerprint(), formulaVersion, formulaVersion,
                stability.read(storeId, period.previous().start().atStartOfDay(zone).toInstant(),
                        period.current().end().plusDays(1).atStartOfDay(zone).toInstant()),
                coverage.read(storeId, period), revisions.read(storeId));
    }
}
