package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3ReadResult.State.CURRENT;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3ReadResult.State.PREPARING;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3ReadResult.State.STALE;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Exact period read: no current-roster fallback, generation or provider calls. */
@Service
class SellerWeeklyHistoricalReadService {
    private final JdbcTemplate jdbc;
    private final WeeklyReviewSnapshotStore snapshots;
    private final SellerWeeklyHistoricalIdentityFactsSource metadata;
    private final SellerWeeklySourceRevisionRepository revisions;
    private final Clock clock;

    SellerWeeklyHistoricalReadService(JdbcTemplate jdbc, WeeklyReviewSnapshotStore snapshots,
            SellerWeeklyHistoricalIdentityFactsSource metadata, SellerWeeklySourceRevisionRepository revisions,
            Clock clock) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.metadata = metadata;
        this.revisions = revisions;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SellerWeeklyV3ReadResult assess(UUID storeId, LocalDate periodStart) {
        return assess(storeId, periodStart, null);
    }

    SellerWeeklyV3ReadResult assessFenced(SellerWeeklyAiSourceFence.LockedSource locked, LocalDate periodStart) {
        return assess(locked.storeId(), periodStart, locked);
    }

    private SellerWeeklyV3ReadResult assess(UUID storeId, LocalDate periodStart,
            SellerWeeklyAiSourceFence.LockedSource locked) {
        requireNonNull(storeId, "storeId");
        if (periodStart == null || periodStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new InvalidRequestException("Historical seller period must start on Monday");
        }
        var stores = jdbc.query("SELECT timezone, is_active FROM stores WHERE id=?",
                (row, index) -> new Store(row.getString("timezone"), row.getBoolean("is_active")), storeId);
        if (stores.isEmpty()) {
            throw new StoreNotFoundException(storeId);
        }
        Store selected = stores.getFirst();
        var week = new ClosedSellerWeek(periodStart, ZoneId.of(selected.timezone()));
        var assessedAt = clock.instant();
        if (!week.isClosedAt(assessedAt)) {
            throw new InvalidRequestException("Historical seller period must be closed");
        }
        var period = SellerWeeklyHistoricalIdentityFactsSource.period(week);
        var saved = snapshots.findLatestV3(storeId, period.current()).filter(value ->
                SellerWeeklyHistoricalMembership.BASIS.equals(value.response().membership().basis()));
        if (saved.isEmpty()) {
            return new SellerWeeklyV3ReadResult(PREPARING, Optional.empty());
        }
        var checkpoint = snapshots.findV3GenerationState(storeId, period.current());
        var snapshot = saved.orElseThrow();
        var report = snapshot.response();
        if (!selected.active() || !selected.timezone().equals(report.period().timezone())
                || !period.previous().equals(report.period().previous())
                || !SellerWeeklyV3Assembler.historicalVersions().equals(report.versions())
                || checkpoint.isEmpty()) {
            return new SellerWeeklyV3ReadResult(STALE, saved);
        }
        var state = checkpoint.orElseThrow();
        if (!snapshot.id().equals(state.snapshotId()) || !snapshot.id().equals(state.latestSnapshotId())
                || (!"CREATED".equals(state.outcome()) && !"REUSED".equals(state.outcome()))
                || state.evaluatedAt().isAfter(assessedAt)
                || state.sourceRevision() != revisions.read(storeId)) {
            return new SellerWeeklyV3ReadResult(STALE, saved);
        }
        try {
            var facts = locked == null ? metadata.load(storeId, week, assessedAt)
                    : metadata.loadFenced(locked, week, assessedAt);
            boolean current = state.sourceRevision() == facts.sourceRevision()
                    && state.identityHash().equals(SellerWeeklyHistoricalIdentity.sourceHash(facts));
            // A formerly latest report with future actions needs a free revision after a week boundary.
            boolean hasFutureActions = !report.actions().isEmpty()
                    || report.employees().stream().anyMatch(item -> item.card().action() != null);
            var completedAt = clock.instant();
            if (!week.isClosedAt(completedAt)) {
                current = false;
            }
            if (hasFutureActions && !week.start().equals(ClosedSellerWeek.latest(
                    completedAt, selected.timezone()).start())) {
                current = false;
            }
            return new SellerWeeklyV3ReadResult(current ? CURRENT : STALE, saved);
        } catch (SellerHistoricalFactsUnavailableException unavailable) {
            return new SellerWeeklyV3ReadResult(STALE, saved);
        }
    }

    private record Store(String timezone, boolean active) { }
}
