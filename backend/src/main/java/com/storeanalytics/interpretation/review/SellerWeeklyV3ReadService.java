package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3ReadResult.State.CURRENT;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3ReadResult.State.PREPARING;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3ReadResult.State.STALE;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Internal-only identity assessment for the v3 planner; public v2 reads never call this. */
@Service
class SellerWeeklyV3ReadService {

    private final JdbcTemplate jdbc;
    private final SellerWeeklyIdentityFactsSource factsSource;
    private final SellerWeeklySourceIdentity identity;
    private final SellerWeeklySourceRevisionRepository sourceRevisions;
    private final WeeklyReviewSnapshotStore snapshots;
    private final Clock clock;
    private final WeeklyReviewPolicyV1 periodPolicy = new WeeklyReviewPolicyV1();

    SellerWeeklyV3ReadService(JdbcTemplate jdbc, SellerWeeklyIdentityFactsSource factsSource,
                              SellerWeeklySourceIdentity identity,
                              SellerWeeklySourceRevisionRepository sourceRevisions,
                              WeeklyReviewSnapshotStore snapshots, Clock clock) {
        this.jdbc = jdbc;
        this.factsSource = factsSource;
        this.identity = identity;
        this.sourceRevisions = sourceRevisions;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    SellerWeeklyV3ReadResult assessForPlanning(UUID storeId) {
        return assess(storeId);
    }

    SellerWeeklyV3ReadResult assessFenced(SellerWeeklyAiSourceFence.LockedSource locked) {
        return assess(locked.storeId());
    }

    private SellerWeeklyV3ReadResult assess(UUID storeId) {
        UUID selectedStore = requireNonNull(storeId, "storeId");
        String timezone = jdbc.queryForObject("SELECT timezone FROM stores WHERE id = ?",
                String.class, selectedStore);
        if (timezone == null) {
            throw new IllegalArgumentException("Store does not exist: " + selectedStore);
        }
        Instant assessedAt = clock.instant();
        PeriodContext period = periodPolicy.period(assessedAt, timezone);
        Optional<WeeklyReviewSnapshotStore.V3GenerationState> checkpoint =
                snapshots.findV3GenerationState(selectedStore, period.current());
        if (checkpoint.isEmpty()) {
            Optional<PersistedWeeklyReviewV3Snapshot> previous = snapshots.findLatestV3(
                    selectedStore, period.current());
            return previous.map(snapshot -> new SellerWeeklyV3ReadResult(STALE,
                            Optional.of(snapshot)))
                    .orElseGet(() -> new SellerWeeklyV3ReadResult(PREPARING, Optional.empty()));
        }
        WeeklyReviewSnapshotStore.V3GenerationState state = checkpoint.orElseThrow();
        Optional<PersistedWeeklyReviewV3Snapshot> compatible = snapshots.findV3ById(state.snapshotId());
        if (compatible.isEmpty() || !selectedStore.equals(compatible.get().storeId())
                || !period.current().equals(compatible.get().response().period().current())) {
            return new SellerWeeklyV3ReadResult(PREPARING, Optional.empty());
        }
        if (!state.snapshotId().equals(state.latestSnapshotId())
                || (!"CREATED".equals(state.outcome()) && !"REUSED".equals(state.outcome()))) {
            return new SellerWeeklyV3ReadResult(STALE, compatible);
        }
        if (!LocalDate.ofInstant(state.evaluatedAt(), ZoneId.of(timezone))
                .equals(LocalDate.ofInstant(assessedAt, ZoneId.of(timezone)))
                || state.sourceRevision() != sourceRevisions.read(selectedStore)) {
            return new SellerWeeklyV3ReadResult(STALE, compatible);
        }
        SellerWeeklyIdentityFacts facts = factsSource.load(selectedStore, assessedAt, timezone);
        boolean current = state.sourceRevision() == facts.sourceRevision()
                && state.identityHash().equals(identity.hash(facts));
        try {
            SellerWeeklyTemporalFence.verify(facts, clock.instant());
        } catch (SellerWeeklySourceChangedException changed) {
            current = false;
        }
        return new SellerWeeklyV3ReadResult(current ? CURRENT : STALE, compatible);
    }
}
