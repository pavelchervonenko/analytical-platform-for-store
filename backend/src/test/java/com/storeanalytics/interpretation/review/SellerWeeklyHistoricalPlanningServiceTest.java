package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerWeeklyHistoricalPlanningServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final LocalDate START = LocalDate.parse("2026-09-14");
    private final UUID store = UUID.randomUUID();
    private final SellerWeeklyHistoricalReadService reads = mock(SellerWeeklyHistoricalReadService.class);
    private final SellerWeeklyHistoricalFactsSource facts = mock(SellerWeeklyHistoricalFactsSource.class);
    private final WeeklyReviewSnapshotStore snapshots = mock(WeeklyReviewSnapshotStore.class);
    private final SellerWeeklyHistoricalPlanningService planner = new SellerWeeklyHistoricalPlanningService(
            reads, facts, snapshots, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void currentExactPeriodDoesNotRunHeavyPreparationOrWrite() {
        var report = mock(WeeklyReviewV3Response.class);
        var saved = mock(PersistedWeeklyReviewV3Snapshot.class);
        when(saved.response()).thenReturn(report);
        var period = SellerWeeklyHistoricalIdentityFactsSource.period(new ClosedSellerWeek(START, ZoneOffset.UTC));
        when(report.period()).thenReturn(period);
        when(reads.assess(store, START)).thenReturn(new SellerWeeklyV3ReadResult(
                SellerWeeklyV3ReadResult.State.CURRENT, Optional.of(saved)));
        assertThat(planner.evaluate(store, START, "Z").outcome())
                .isEqualTo(SellerWeeklyV3PlanningResult.Outcome.UNCHANGED);
        verifyNoInteractions(facts, snapshots);
    }

    @Test
    void preparesOnlyTheRequestedOldWeekOnceAndThenReassesses() {
        var before = new SellerWeeklyV3ReadResult(SellerWeeklyV3ReadResult.State.PREPARING, Optional.empty());
        when(reads.assess(store, START)).thenReturn(before);
        var selected = mock(SellerWeeklyHistoricalFacts.class);
        when(facts.load(store, START, "UTC", NOW)).thenReturn(selected);
        assertThat(planner.evaluate(store, START, "UTC").outcome())
                .isEqualTo(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        verify(snapshots).persistHistoricalCandidate(selected, NOW);
    }

    @Test
    void sourceChangeDefersWithoutAnImmediateHeavyRetry() {
        when(reads.assess(store, START)).thenReturn(new SellerWeeklyV3ReadResult(
                SellerWeeklyV3ReadResult.State.PREPARING, Optional.empty()));
        var selected = mock(SellerWeeklyHistoricalFacts.class);
        when(facts.load(store, START, "UTC", NOW)).thenReturn(selected);
        when(snapshots.persistHistoricalCandidate(selected, NOW)).thenThrow(new SellerWeeklySourceChangedException());
        assertThat(planner.evaluate(store, START, "UTC").outcome())
                .isEqualTo(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        verify(facts).load(store, START, "UTC", NOW);
    }

    @Test
    void unknownHistoryDoesNotWriteCurrentRosterFallback() {
        when(reads.assess(store, START)).thenReturn(new SellerWeeklyV3ReadResult(
                SellerWeeklyV3ReadResult.State.PREPARING, Optional.empty()));
        when(facts.load(store, START, "UTC", NOW))
                .thenThrow(new SellerHistoricalFactsUnavailableException("HISTORY_BASELINE_UNAVAILABLE"));
        assertThat(planner.evaluate(store, START, "UTC").outcome())
                .isEqualTo(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        verifyNoInteractions(snapshots);
    }
}
