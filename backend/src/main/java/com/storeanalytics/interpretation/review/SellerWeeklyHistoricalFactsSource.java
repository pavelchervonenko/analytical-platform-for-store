package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.metrics.service.SellerHistoricalFactsService;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.store.service.StoreDataStatusService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Period-specific, free preparation. Does not claim the legacy current-roster snapshot identity. */
@Component
class SellerWeeklyHistoricalFactsSource {
    private final JdbcTemplate jdbc;
    private final SellerHistoricalFactsService sellers;
    private final StoreDataStatusService dataStatus;
    private final SellerWeeklySourceStabilityRepository stability;
    private final SellerWeeklySourceCoverageRepository coverage;
    private final SellerWeeklySourceRevisionRepository revision;

    SellerWeeklyHistoricalFactsSource(JdbcTemplate jdbc, SellerHistoricalFactsService sellers,
            StoreDataStatusService dataStatus, SellerWeeklySourceStabilityRepository stability,
            SellerWeeklySourceCoverageRepository coverage, SellerWeeklySourceRevisionRepository revision) {
        this.jdbc = jdbc;
        this.sellers = sellers;
        this.dataStatus = dataStatus;
        this.stability = stability;
        this.coverage = coverage;
        this.revision = revision;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SellerWeeklyHistoricalFacts load(UUID storeId, LocalDate start, String expectedTimezone, Instant now) {
        requireNonNull(storeId, "storeId");
        requireNonNull(expectedTimezone, "expectedTimezone");
        requireNonNull(now, "now");
        List<String> zones = jdbc.queryForList(
                "SELECT timezone FROM stores WHERE id = ? AND is_active", String.class, storeId);
        if (zones.isEmpty() || !zones.getFirst().equals(expectedTimezone)) {
            throw new SellerHistoricalFactsUnavailableException("STORE_CONFIGURATION_CHANGED");
        }
        ClosedSellerWeek week = new ClosedSellerWeek(start, ZoneId.of(expectedTimezone));
        if (!week.isClosedAt(now)) {
            throw new SellerHistoricalFactsUnavailableException("WEEK_NOT_CLOSED");
        }
        DateRange current = new DateRange(week.start(), week.end());
        DateRange previous = new DateRange(week.previous().start(), week.previous().end());
        PeriodContext period = new PeriodContext(expectedTimezone, current, previous,
                current.start() + " — " + current.end(), previous.start() + " — " + previous.end());
        SellerWeeklySourceCoverage selectedCoverage = coverage.read(storeId, period);
        if (!selectedCoverage.completeBothWeeks()) {
            throw new SellerHistoricalFactsUnavailableException("SOURCE_COVERAGE_INCOMPLETE");
        }
        SellerWeeklySourceStability selectedStability = stability.read(
                storeId, week.previous().startInstant(), week.closesAt());
        if (selectedStability != SellerWeeklySourceStability.STABLE) {
            String reason = selectedStability == SellerWeeklySourceStability.IN_PROGRESS
                    ? "SOURCE_WRITES_ACTIVE" : "SOURCE_RECONCILIATION_REQUIRED";
            throw new SellerHistoricalFactsUnavailableException(reason);
        }
        var comparison = sellers.read(storeId, new StoreKpiPeriod(current.start(), current.end()),
                new StoreKpiPeriod(previous.start(), previous.end()), week.zone(), now);
        var status = dataStatus.get(storeId);
        return new SellerWeeklyHistoricalFacts(storeId, period, status, comparison,
                status.lastCompletedSyncAt(), selectedStability, selectedCoverage, revision.read(storeId));
    }
}
