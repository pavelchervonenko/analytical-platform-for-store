package com.storeanalytics.interpretation.review;

import static com.storeanalytics.interpretation.review.SellerWeeklyV3ReadResult.State.CURRENT;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3ReadResult.State.PREPARING;
import static com.storeanalytics.interpretation.review.SellerWeeklyV3ReadResult.State.STALE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SellerWeeklyV3ReadServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T04:00:00Z");
    private static final String ZONE = "Europe/Kaliningrad";
    private static final String HASH = "a".repeat(64);
    private static final PeriodContext PERIOD = new WeeklyReviewPolicyV1().period(NOW, ZONE);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SellerWeeklyIdentityFactsSource factsSource = mock(SellerWeeklyIdentityFactsSource.class);
    private final SellerWeeklySourceIdentity identity = mock(SellerWeeklySourceIdentity.class);
    private final SellerWeeklySourceRevisionRepository sourceRevisions =
            mock(SellerWeeklySourceRevisionRepository.class);
    private final WeeklyReviewSnapshotStore snapshots = mock(WeeklyReviewSnapshotStore.class);
    private final Clock clock = mock(Clock.class);
    private final SellerWeeklyV3ReadService service = new SellerWeeklyV3ReadService(
            jdbc, factsSource, identity, sourceRevisions, snapshots, clock);

    @Test
    void noCheckpointAndNoSnapshotIsPreparingWithoutReadingHeavyFacts() {
        UUID storeId = UUID.randomUUID();
        arrangeStore(storeId);

        SellerWeeklyV3ReadResult result = service.assessForPlanning(storeId);

        assertThat(result.state()).isEqualTo(PREPARING);
        assertThat(result.snapshot()).isEmpty();
        verify(factsSource, never()).load(storeId, NOW, ZONE);
    }

    @Test
    void orphanV3SnapshotWithoutCheckpointIsStaleNotCurrent() {
        UUID storeId = UUID.randomUUID();
        arrangeStore(storeId);
        PersistedWeeklyReviewV3Snapshot previous = snapshot(storeId);
        when(snapshots.findLatestV3(storeId, PERIOD.current())).thenReturn(Optional.of(previous));

        SellerWeeklyV3ReadResult result = service.assessForPlanning(storeId);

        assertThat(result.state()).isEqualTo(STALE);
        assertThat(result.snapshot()).contains(previous);
    }

    @Test
    void unchangedCanonicalIdentityAndLatestCompatibleSnapshotAreCurrent() {
        UUID storeId = UUID.randomUUID();
        PersistedWeeklyReviewV3Snapshot saved = arrangeCheckpoint(storeId, true);
        SellerWeeklyIdentityFacts facts = facts();
        when(factsSource.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(identity.hash(facts)).thenReturn(HASH);

        SellerWeeklyV3ReadResult result = service.assessForPlanning(storeId);

        assertThat(result.state()).isEqualTo(CURRENT);
        assertThat(result.snapshot()).contains(saved);
    }

    @Test
    void changedSourceIdentityKeepsLastCompatibleSnapshotButMarksItStale() {
        UUID storeId = UUID.randomUUID();
        PersistedWeeklyReviewV3Snapshot saved = arrangeCheckpoint(storeId, true);
        SellerWeeklyIdentityFacts facts = facts();
        when(factsSource.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(identity.hash(facts)).thenReturn("b".repeat(64));

        SellerWeeklyV3ReadResult result = service.assessForPlanning(storeId);

        assertThat(result.state()).isEqualTo(STALE);
        assertThat(result.snapshot()).contains(saved);
    }

    @Test
    void inconsistentMetadataRevisionCannotBeCurrentEvenWhenHashMatches() {
        UUID storeId = UUID.randomUUID();
        PersistedWeeklyReviewV3Snapshot saved = arrangeCheckpoint(storeId, true);
        SellerWeeklyIdentityFacts facts = facts();
        when(facts.sourceRevision()).thenReturn(5L);
        when(factsSource.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(identity.hash(facts)).thenReturn(HASH);

        SellerWeeklyV3ReadResult result = service.assessForPlanning(storeId);

        assertThat(result.state()).isEqualTo(STALE);
        assertThat(result.snapshot()).contains(saved);
    }

    @Test
    void changedSourceRevisionSkipsHeavyFactsAndKeepsSnapshotStale() {
        UUID storeId = UUID.randomUUID();
        PersistedWeeklyReviewV3Snapshot saved = arrangeCheckpoint(storeId, true);
        when(sourceRevisions.read(storeId)).thenReturn(5L);

        SellerWeeklyV3ReadResult result = service.assessForPlanning(storeId);

        assertThat(result.state()).isEqualTo(STALE);
        assertThat(result.snapshot()).contains(saved);
        verify(factsSource, never()).load(storeId, NOW, ZONE);
    }

    @Test
    void nextLocalDaySkipsHeavyFactsEvenWhenDatabaseRevisionIsUnchanged() {
        UUID storeId = UUID.randomUUID();
        PersistedWeeklyReviewV3Snapshot saved = arrangeCheckpoint(storeId, true);
        when(clock.instant()).thenReturn(Instant.parse("2026-08-25T04:00:00Z"));

        SellerWeeklyV3ReadResult result = service.assessForPlanning(storeId);

        assertThat(result.state()).isEqualTo(STALE);
        assertThat(result.snapshot()).contains(saved);
        verify(factsSource, never()).load(storeId, NOW, ZONE);
    }

    @Test
    void v2RollbackAfterCheckpointCannotBeReadAsCurrentV3() {
        UUID storeId = UUID.randomUUID();
        PersistedWeeklyReviewV3Snapshot saved = arrangeCheckpoint(storeId, false);

        SellerWeeklyV3ReadResult result = service.assessForPlanning(storeId);

        assertThat(result.state()).isEqualTo(STALE);
        assertThat(result.snapshot()).contains(saved);
        verify(factsSource, never()).load(storeId, NOW, ZONE);
    }

    @Test
    void localMidnightDuringReadPreventsCurrentClassification() {
        UUID storeId = UUID.randomUUID();
        PersistedWeeklyReviewV3Snapshot saved = arrangeCheckpoint(storeId, true);
        SellerWeeklyIdentityFacts facts = facts();
        when(factsSource.load(storeId, NOW, ZONE)).thenReturn(facts);
        when(identity.hash(facts)).thenReturn(HASH);
        when(clock.instant()).thenReturn(NOW, Instant.parse("2026-08-24T22:00:00Z"));

        SellerWeeklyV3ReadResult result = service.assessForPlanning(storeId);

        assertThat(result.state()).isEqualTo(STALE);
        assertThat(result.snapshot()).contains(saved);
    }

    private void arrangeStore(UUID storeId) {
        when(jdbc.queryForObject("SELECT timezone FROM stores WHERE id = ?", String.class, storeId))
                .thenReturn(ZONE);
        when(clock.instant()).thenReturn(NOW);
    }

    private PersistedWeeklyReviewV3Snapshot arrangeCheckpoint(UUID storeId, boolean latest) {
        arrangeStore(storeId);
        PersistedWeeklyReviewV3Snapshot saved = snapshot(storeId);
        UUID savedId = saved.id();
        UUID latestId = latest ? savedId : UUID.randomUUID();
        WeeklyReviewSnapshotStore.V3GenerationState checkpoint =
                new WeeklyReviewSnapshotStore.V3GenerationState(HASH, 4, savedId, NOW,
                        "REUSED", latestId);
        when(snapshots.findV3GenerationState(storeId, PERIOD.current())).thenReturn(Optional.of(
                checkpoint));
        when(snapshots.findV3ById(savedId)).thenReturn(Optional.of(saved));
        when(sourceRevisions.read(storeId)).thenReturn(4L);
        return saved;
    }

    private PersistedWeeklyReviewV3Snapshot snapshot(UUID storeId) {
        PersistedWeeklyReviewV3Snapshot saved = mock(PersistedWeeklyReviewV3Snapshot.class);
        WeeklyReviewV3Response response = mock(WeeklyReviewV3Response.class);
        when(saved.id()).thenReturn(UUID.randomUUID());
        when(saved.storeId()).thenReturn(storeId);
        when(saved.response()).thenReturn(response);
        when(response.period()).thenReturn(PERIOD);
        return saved;
    }

    private SellerWeeklyIdentityFacts facts() {
        SellerWeeklyIdentityFacts facts = mock(SellerWeeklyIdentityFacts.class);
        StoreDataStatusView status = mock(StoreDataStatusView.class);
        when(facts.period()).thenReturn(PERIOD);
        when(facts.sourceDataStatus()).thenReturn(status);
        when(status.expectedThroughDate()).thenReturn(LocalDate.of(2026, 8, 23));
        when(facts.sourceRevision()).thenReturn(4L);
        return facts;
    }
}
