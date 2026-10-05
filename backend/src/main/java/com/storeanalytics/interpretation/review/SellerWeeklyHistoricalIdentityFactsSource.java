package com.storeanalytics.interpretation.review;

import com.storeanalytics.metrics.repository.SellerAttachRateRepository;
import com.storeanalytics.metrics.repository.SellerCohortRepository;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Cheap period metadata; never runs financial/attach aggregates, writes a checkpoint or starts AI. */
@Component
class SellerWeeklyHistoricalIdentityFactsSource {
    private final SellerMembershipHistoryRepository history;
    private final SellerCohortRepository current;
    private final SellerAttachRateRepository attach;
    private final SellerWeeklyHistoricalIdentity identity;
    private final SellerWeeklySourceCoverageRepository coverage;
    private final SellerWeeklySourceStabilityRepository stability;
    private final SellerWeeklySourceRevisionRepository revisions;

    SellerWeeklyHistoricalIdentityFactsSource(SellerMembershipHistoryRepository history,
            SellerCohortRepository current, SellerAttachRateRepository attach, SellerWeeklyHistoricalIdentity identity,
            SellerWeeklySourceCoverageRepository coverage, SellerWeeklySourceStabilityRepository stability,
            SellerWeeklySourceRevisionRepository revisions) {
        this.history = history;
        this.current = current;
        this.attach = attach;
        this.identity = identity;
        this.coverage = coverage;
        this.stability = stability;
        this.revisions = revisions;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SellerWeeklyHistoricalIdentityFacts load(UUID storeId, ClosedSellerWeek week, Instant now) {
        return read(storeId, week, now, null);
    }

    SellerWeeklyHistoricalIdentityFacts loadFenced(SellerWeeklyAiSourceFence.LockedSource locked,
            ClosedSellerWeek week, Instant now) {
        return read(locked.storeId(), week, now, locked);
    }

    private SellerWeeklyHistoricalIdentityFacts read(UUID storeId, ClosedSellerWeek week, Instant now,
            SellerWeeklyAiSourceFence.LockedSource locked) {
        if (!week.isClosedAt(now)) {
            throw new SellerHistoricalFactsUnavailableException("WEEK_NOT_CLOSED");
        }
        var period = period(week);
        if (!coverage.read(storeId, period).completeBothWeeks()) {
            throw new SellerHistoricalFactsUnavailableException("SOURCE_COVERAGE_INCOMPLETE");
        }
        if (stability.read(storeId, week.previous().startInstant(), week.closesAt())
                != SellerWeeklySourceStability.STABLE) {
            throw new SellerHistoricalFactsUnavailableException("SOURCE_NOT_STABLE");
        }
        var selected = new SellerCohortSnapshot(storeId,
                history.eligibleDuring(storeId, week.previous().startInstant(), week.closesAt()));
        var actionIds = new HashSet<>(current.read(storeId).employeeIds());
        actionIds.retainAll(selected.employeeIds());
        var membership = locked == null ? identity.read(storeId, week, selected, actionIds)
                : identity.readFenced(locked, week, selected, actionIds);
        return new SellerWeeklyHistoricalIdentityFacts(storeId, period, revisions.read(storeId), membership,
                attach.historicalFormulaVersion());
    }

    static WeeklyReviewResponse.PeriodContext period(ClosedSellerWeek week) {
        var current = new WeeklyReviewResponse.DateRange(week.start(), week.end());
        var previous = new WeeklyReviewResponse.DateRange(week.previous().start(), week.previous().end());
        return new WeeklyReviewResponse.PeriodContext(week.zone().getId(), current, previous,
                label(current.start(), current.end()), label(previous.start(), previous.end()));
    }

    private static String label(LocalDate from, LocalDate through) {
        return from + " — " + through;
    }
}
