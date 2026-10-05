package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.PersistedWeeklyReviewV3Snapshot;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyHistoricalReviewService;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewView;
import com.storeanalytics.interpretation.review.SellerWeeklyV3AssemblerTest;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SellerWeeklyReviewAiFreshnessGuardTest {

    private static final Instant NOW = Instant.parse("2026-08-24T08:00:00Z");
    private final SellerWeeklyReviewService reviews = mock(SellerWeeklyReviewService.class);
    private final SellerWeeklyReviewAiFreshnessGuard guard = new SellerWeeklyReviewAiFreshnessGuard(reviews);
    private final UUID store = UUID.randomUUID();

    @Test
    void refreshAcceptsOnlyTheSameExactImmutableSnapshot() {
        var snapshot = snapshot(UUID.randomUUID());
        when(reviews.current(store)).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, snapshot.response()));
        assertThat(guard.refreshIfSameSnapshot(snapshot, NOW)).isTrue();
        verify(reviews).generate(store);
    }

    @Test
    void changedRevisionCannotReplaceTheApprovedSnapshot() {
        var snapshot = snapshot(UUID.randomUUID());
        var newer = snapshot(UUID.randomUUID());
        when(reviews.current(store)).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, newer.response()));
        assertThat(guard.refreshIfSameSnapshot(snapshot, NOW)).isFalse();
    }

    @Test
    void incompleteSourcesRemainIneligibleAfterFreePreparation() {
        var snapshot = snapshot(UUID.randomUUID());
        when(reviews.current(store)).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.PREPARING, null));
        assertThat(guard.refreshIfSameSnapshot(snapshot, NOW)).isFalse();
    }

    @Test
    void oldWeekCannotTriggerGenerationForAnUnrelatedNewWeek() {
        assertThat(guard.refreshIfSameSnapshot(snapshot(UUID.randomUUID()), NOW.plusSeconds(14 * 86400))).isFalse();
        verify(reviews, never()).generate(store);
    }

    @Test
    void readOnlyFreshnessCheckNeverGeneratesASnapshot() {
        var snapshot = snapshot(UUID.randomUUID());
        when(reviews.current(store)).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.STALE, snapshot.response()));
        assertThat(guard.isCurrent(snapshot)).isFalse();
        verify(reviews, never()).generate(store);
    }

    @Test
    void historicalExactWeekUsesPeriodReadNotUnrelatedLatestRoster() {
        var historical = mock(SellerWeeklyHistoricalReviewService.class);
        var selectedGuard = new SellerWeeklyReviewAiFreshnessGuard(reviews, historical);
        var snapshot = historicalSnapshot(UUID.randomUUID());
        var start = snapshot.response().period().current().start();
        when(historical.period(store, start)).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, snapshot.response()));
        assertThat(selectedGuard.isCurrent(snapshot)).isTrue();
        verify(historical).period(store, start);
        org.mockito.Mockito.verifyNoInteractions(reviews);
    }

    @Test
    void oldHistoricalSnapshotCanBeFreelyRefreshedButOnlyTheExactImmutableIdAccepted() {
        var historical = mock(SellerWeeklyHistoricalReviewService.class);
        var selectedGuard = new SellerWeeklyReviewAiFreshnessGuard(reviews, historical);
        var snapshot = historicalSnapshot(UUID.randomUUID());
        var period = snapshot.response().period();
        when(historical.period(store, period.current().start())).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, snapshot.response()));
        assertThat(selectedGuard.refreshIfSameSnapshot(snapshot, NOW.plusSeconds(14 * 86400))).isTrue();
        verify(historical).refresh(store, period.current().start(), period.timezone());
        var newer = historicalSnapshot(UUID.randomUUID());
        when(historical.period(store, period.current().start())).thenReturn(new SellerWeeklyReviewView(
                SellerWeeklyReviewView.Freshness.CURRENT, newer.response()));
        assertThat(selectedGuard.refreshIfSameSnapshot(snapshot, NOW.plusSeconds(14 * 86400))).isFalse();
        org.mockito.Mockito.verifyNoInteractions(reviews);
    }

    @Test
    void historicalSupportMissingOrPeriodOpenCannotTriggerLegacyGeneration() {
        var snapshot = historicalSnapshot(UUID.randomUUID());
        assertThat(guard.isCurrent(snapshot)).isFalse();
        assertThat(guard.refreshIfSameSnapshot(snapshot, NOW)).isFalse();
        var historical = mock(SellerWeeklyHistoricalReviewService.class);
        var selectedGuard = new SellerWeeklyReviewAiFreshnessGuard(reviews, historical);
        assertThat(selectedGuard.refreshIfSameSnapshot(snapshot, Instant.parse("2026-08-23T12:00:00Z"))).isFalse();
        org.mockito.Mockito.verifyNoInteractions(reviews, historical);
    }

    private PersistedWeeklyReviewV3Snapshot historicalSnapshot(UUID id) {
        var snapshot = snapshot(id);
        when(snapshot.response().membership()).thenReturn(new WeeklyReviewV3Response.Membership(
                "HISTORICAL_DOCUMENT_MEMBERSHIP_V1", "a".repeat(64), "a".repeat(64), "b".repeat(64), NOW, 1));
        return snapshot;
    }

    private PersistedWeeklyReviewV3Snapshot snapshot(UUID id) {
        var period = SellerWeeklyV3AssemblerTest.syntheticResponse().period();
        var report = mock(WeeklyReviewV3Response.class);
        when(report.contractVersion()).thenReturn(3);
        when(report.period()).thenReturn(period);
        when(report.provenance()).thenReturn(
                new WeeklyReviewResponse.Provenance(id.toString(), 1, NOW, NOW, false, null));
        when(report.reportState()).thenReturn(WeeklyReviewResponse.ReportState.READY);
        return new PersistedWeeklyReviewV3Snapshot(id, store, 1, null, report, "a".repeat(64), NOW);
    }
}
