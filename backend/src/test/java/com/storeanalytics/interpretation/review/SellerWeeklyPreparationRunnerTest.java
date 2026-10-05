package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.SellerWeeklyPreparationStore.Claim;
import com.storeanalytics.interpretation.review.SellerWeeklyPreparationStore.Deferral;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class SellerWeeklyPreparationRunnerTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration BACKOFF = Duration.ofMinutes(15);
    private final SellerWeeklyPreparationStore queue = mock(SellerWeeklyPreparationStore.class);
    private final SellerWeeklyHistoricalFactsSource facts = mock(SellerWeeklyHistoricalFactsSource.class);
    private final WeeklyReviewSnapshotStore snapshots = mock(WeeklyReviewSnapshotStore.class);
    private final SellerWeeklyPreparationRunner runner = new SellerWeeklyPreparationRunner(queue, facts, snapshots,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final Claim claim = new Claim(UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-09-14"),
            "UTC", "owner", UUID.randomUUID(), 1, NOW.plus(LEASE));
    private final SellerWeeklyHistoricalFacts selected = mock(SellerWeeklyHistoricalFacts.class);

    @BeforeEach
    void configure() {
        when(queue.claimNext("owner", LEASE, NOW)).thenReturn(Optional.of(claim));
        when(facts.load(claim.storeId(), claim.periodStart(), "UTC", NOW)).thenReturn(selected);
        when(queue.heartbeat(claim, LEASE, NOW)).thenReturn(true);
    }

    @Test
    void oneInvocationPreparesTheClaimedOldWeekAndBindsItsExactSnapshot() {
        UUID id = UUID.randomUUID();
        var snapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        when(snapshot.id()).thenReturn(id);
        when(snapshots.persistHistoricalCandidate(selected, NOW)).thenReturn(snapshot);
        when(queue.completeWithSnapshot(claim, id, NOW)).thenReturn(true);
        assertThat(runner.prepareNext("owner"))
                .isEqualTo(new SellerWeeklyPreparationRunner.Result(claim.id(), "SUCCEEDED", id));
        verify(facts).load(claim.storeId(), claim.periodStart(), "UTC", NOW);
        verify(queue).heartbeat(claim, LEASE, NOW);
        verify(queue).completeWithSnapshot(claim, id, NOW);
    }

    @Test
    void emptyQueueDoesNotPrepareOrWrite() {
        when(queue.claimNext("owner", LEASE, NOW)).thenReturn(Optional.empty());
        assertThat(runner.prepareNext("owner").state()).isEqualTo("IDLE");
        verifyNoInteractions(facts, snapshots);
    }

    @Test
    void lostLeaseAfterFactsCannotWriteOrReviveOwnership() {
        when(queue.heartbeat(claim, LEASE, NOW)).thenReturn(false);
        assertThat(runner.prepareNext("owner").state()).isEqualTo("LEASE_LOST");
        verifyNoInteractions(snapshots);
    }

    @ParameterizedTest
    @CsvSource({"SOURCE_COVERAGE_INCOMPLETE,WAITING_SOURCES", "SOURCE_WRITES_ACTIVE,WAITING_SOURCES",
            "SOURCE_RECONCILIATION_REQUIRED,WAITING_SOURCES", "MEMBERSHIP_BASELINE_MISSING,WAITING_HISTORY",
            "DOCUMENT_MEMBERSHIP_OR_AUTHOR_UNKNOWN,WAITING_HISTORY",
            "ATTACH_MEMBERSHIP_OR_AUTHOR_UNKNOWN,WAITING_HISTORY"})
    void safeWaitsHaveFreeBackoffWithoutSnapshotWrite(String reason, Deferral state) {
        when(facts.load(claim.storeId(), claim.periodStart(), "UTC", NOW))
                .thenThrow(new SellerHistoricalFactsUnavailableException(reason));
        when(queue.defer(claim, state, reason, BACKOFF, NOW)).thenReturn(true);
        assertThat(runner.prepareNext("owner").state()).isEqualTo(state.name());
        verify(queue).defer(claim, state, reason, BACKOFF, NOW);
        verifyNoInteractions(snapshots);
    }

    @Test
    void sourceChangeBeforeWriteDefersTheSameJobInsteadOfImmediateHeavyRetry() {
        when(snapshots.persistHistoricalCandidate(selected, NOW)).thenThrow(new SellerWeeklySourceChangedException());
        when(queue.defer(claim, Deferral.WAITING_SOURCES, "SOURCE_CHANGED", BACKOFF, NOW)).thenReturn(true);
        assertThat(runner.prepareNext("owner").state()).isEqualTo("WAITING_SOURCES");
        verify(queue).defer(claim, Deferral.WAITING_SOURCES, "SOURCE_CHANGED", BACKOFF, NOW);
    }

    @Test
    void changeBetweenWriteAndBindingCannotMarkSuccess() {
        UUID id = UUID.randomUUID();
        var snapshot = mock(PersistedWeeklyReviewV3Snapshot.class);
        when(snapshot.id()).thenReturn(id);
        when(snapshots.persistHistoricalCandidate(selected, NOW)).thenReturn(snapshot);
        when(queue.defer(claim, Deferral.WAITING_SOURCES, "SOURCE_OR_LEASE_CHANGED", BACKOFF, NOW)).thenReturn(true);
        assertThat(runner.prepareNext("owner").state()).isEqualTo("WAITING_SOURCES");
        verify(queue).completeWithSnapshot(claim, id, NOW);
    }

    @Test
    void unexpectedMessageIsNeverPersistedAsTheReason() {
        when(facts.load(claim.storeId(), claim.periodStart(), "UTC", NOW))
                .thenThrow(new SellerHistoricalFactsUnavailableException("sensitive-context-not-a-code"));
        when(queue.defer(claim, Deferral.FAILED, "HISTORICAL_CONTRACT_REJECTED", BACKOFF, NOW)).thenReturn(true);
        assertThat(runner.prepareNext("owner").state()).isEqualTo("FAILED");
        verify(queue).defer(claim, Deferral.FAILED, "HISTORICAL_CONTRACT_REJECTED", BACKOFF, NOW);
    }

    @Test
    void technicalFailureIsTerminalAndCannotDeferAfterLeaseLoss() {
        when(snapshots.persistHistoricalCandidate(selected, NOW)).thenThrow(new IllegalStateException("synthetic"));
        assertThat(runner.prepareNext("owner").state()).isEqualTo("LEASE_LOST");
        verify(queue).defer(claim, Deferral.FAILED, "PREPARATION_EXECUTION_FAILED", BACKOFF, NOW);
    }

    @Test
    void runnerDoesNotHoldAnOuterTransactionAcrossHeavyReadOrIndependentWrites() throws Exception {
        assertThat(SellerWeeklyPreparationRunner.class.getMethod("prepareNext", String.class)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }
}
