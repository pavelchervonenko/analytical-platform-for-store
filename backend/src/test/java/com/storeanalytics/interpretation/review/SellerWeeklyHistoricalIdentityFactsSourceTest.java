package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.repository.SellerAttachRateRepository;
import com.storeanalytics.metrics.repository.SellerCohortRepository;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SellerWeeklyHistoricalIdentityFactsSourceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private final UUID store = UUID.randomUUID();
    private final UUID seller = UUID.randomUUID();
    private final UUID departed = UUID.randomUUID();
    private final ClosedSellerWeek week = new ClosedSellerWeek(LocalDate.parse("2026-09-14"), ZoneOffset.UTC);
    private final SellerMembershipHistoryRepository history = mock(SellerMembershipHistoryRepository.class);
    private final SellerCohortRepository current = mock(SellerCohortRepository.class);
    private final SellerAttachRateRepository attach = mock(SellerAttachRateRepository.class);
    private final SellerWeeklyHistoricalIdentity identity = mock(SellerWeeklyHistoricalIdentity.class);
    private final SellerWeeklySourceCoverageRepository coverage = mock(SellerWeeklySourceCoverageRepository.class);
    private final SellerWeeklySourceStabilityRepository stability = mock(SellerWeeklySourceStabilityRepository.class);
    private final SellerWeeklySourceRevisionRepository revisions = mock(SellerWeeklySourceRevisionRepository.class);
    private final SellerWeeklyHistoricalIdentityFactsSource source = new SellerWeeklyHistoricalIdentityFactsSource(
            history, current, attach, identity, coverage, stability, revisions);

    @BeforeEach
    void arrange() {
        when(coverage.read(store, SellerWeeklyHistoricalIdentityFactsSource.period(week)))
                .thenReturn(SellerWeeklySourceCoverage.complete());
        when(stability.read(store, week.previous().startInstant(), week.closesAt()))
                .thenReturn(SellerWeeklySourceStability.STABLE);
        when(history.eligibleDuring(store, week.previous().startInstant(), week.closesAt()))
                .thenReturn(List.of(seller, departed));
        when(current.read(store)).thenReturn(new SellerCohortSnapshot(store, List.of(seller, UUID.randomUUID())));
        when(identity.read(eq(store), eq(week), any(SellerCohortSnapshot.class), eq(Set.of(seller))))
                .thenReturn(new SellerWeeklyHistoricalMembership(Instant.parse("2026-09-01T00:00:00Z"),
                        3, "a".repeat(64), "b".repeat(64)));
        when(revisions.read(store)).thenReturn(7L);
        when(attach.historicalFormulaVersion()).thenReturn("attach-rate-v4-historical-membership-v1");
    }

    @Test
    void readsExactOldPeriodAndSeparatesCurrentActionsWithoutFinancialAggregates() {
        var result = source.load(store, week, NOW);
        assertThat(result.sourceRevision()).isEqualTo(7);
        assertThat(result.membership().revision()).isEqualTo(3);
        assertThat(result.period().current().start()).isEqualTo(week.start());
        verify(identity).read(store, week, new SellerCohortSnapshot(store, List.of(seller, departed)), Set.of(seller));
    }

    @Test
    void incompleteCoverageAndUnstableSourcesDoNotReadMembership() {
        when(coverage.read(store, SellerWeeklyHistoricalIdentityFactsSource.period(week)))
                .thenReturn(new SellerWeeklySourceCoverage(new SellerWeeklySourceCoverage.Window(true, true),
                        new SellerWeeklySourceCoverage.Window(false, true),
                        new SellerWeeklySourceCoverage.Window(true, true)));
        assertThatThrownBy(() -> source.load(store, week, NOW)).hasMessage("SOURCE_COVERAGE_INCOMPLETE");
        when(coverage.read(store, SellerWeeklyHistoricalIdentityFactsSource.period(week)))
                .thenReturn(SellerWeeklySourceCoverage.complete());
        when(stability.read(store, week.previous().startInstant(), week.closesAt()))
                .thenReturn(SellerWeeklySourceStability.IN_PROGRESS);
        assertThatThrownBy(() -> source.load(store, week, NOW)).hasMessage("SOURCE_NOT_STABLE");
        verifyNoInteractions(identity, revisions);
    }

    @Test
    void unknownBaselineIsNotCurrentRosterFallback() {
        when(identity.read(eq(store), eq(week), any(SellerCohortSnapshot.class), eq(Set.of(seller))))
                .thenThrow(new SellerHistoricalFactsUnavailableException("HISTORY_BASELINE_UNAVAILABLE"));
        assertThatThrownBy(() -> source.load(store, week, NOW)).hasMessage("HISTORY_BASELINE_UNAVAILABLE");
        verifyNoInteractions(revisions);
    }

    @Test
    void openWeekIsRejectedBeforeAnyDatabaseMetadataRead() {
        assertThatThrownBy(() -> source.load(store, week, week.closesAt().minusNanos(1)))
                .hasMessage("WEEK_NOT_CLOSED");
        verifyNoInteractions(identity, revisions);
    }

    @Test
    void cheapAndHeavyIdentityHaveTheSameCanonicalHash() {
        var metadata = source.load(store, week, NOW);
        var facts = SellerWeeklyV3AssemblerTest.historicalFacts(store, metadata.period(), true,
                metadata.sourceRevision(), metadata.membership());
        assertThat(SellerWeeklyHistoricalIdentity.sourceHash(metadata))
                .isEqualTo(SellerWeeklyHistoricalIdentity.sourceHash(facts));
    }
}
