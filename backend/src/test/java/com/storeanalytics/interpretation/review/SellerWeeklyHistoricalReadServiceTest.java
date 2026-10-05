package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.metrics.exception.StoreNotFoundException;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SellerWeeklyHistoricalReadServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final LocalDate START = LocalDate.parse("2026-09-14");
    private static final ClosedSellerWeek WEEK = new ClosedSellerWeek(START, ZoneOffset.UTC);
    private final UUID store = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WeeklyReviewSnapshotStore snapshots = mock(WeeklyReviewSnapshotStore.class);
    private final SellerWeeklyHistoricalIdentityFactsSource metadata =
            mock(SellerWeeklyHistoricalIdentityFactsSource.class);
    private final SellerWeeklySourceRevisionRepository revisions = mock(SellerWeeklySourceRevisionRepository.class);
    private final Clock clock = mock(Clock.class);
    private final ResultSet row = mock(ResultSet.class);
    private final SellerWeeklyHistoricalReadService reads = new SellerWeeklyHistoricalReadService(
            jdbc, snapshots, metadata, revisions, clock);
    private final WeeklyReviewV3Response report = mock(WeeklyReviewV3Response.class);
    private final PersistedWeeklyReviewV3Snapshot saved = mock(PersistedWeeklyReviewV3Snapshot.class);
    private final SellerWeeklyHistoricalIdentityFacts facts = new SellerWeeklyHistoricalIdentityFacts(store,
            SellerWeeklyHistoricalIdentityFactsSource.period(WEEK), 4,
            new SellerWeeklyHistoricalMembership(Instant.parse("2026-09-01T00:00:00Z"), 2,
                    "a".repeat(64), "b".repeat(64)), "attach-rate-v4-historical-membership-v1");

    @BeforeEach
    void arrange() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        when(row.getString("timezone")).thenReturn("Z");
        when(row.getBoolean("is_active")).thenReturn(true);
        when(jdbc.query(eq("SELECT timezone, is_active FROM stores WHERE id=?"), any(RowMapper.class), eq(store)))
                .thenAnswer(call -> List.of(((RowMapper<?>) call.getArgument(1)).mapRow(row, 0)));
        when(saved.id()).thenReturn(id);
        when(saved.response()).thenReturn(report);
        when(report.period()).thenReturn(facts.period());
        when(report.versions()).thenReturn(SellerWeeklyV3Assembler.historicalVersions());
        when(report.membership()).thenReturn(new WeeklyReviewV3Response.Membership(
                SellerWeeklyHistoricalMembership.BASIS, "a".repeat(64), "a".repeat(64), "b".repeat(64), NOW, 1));
        when(snapshots.findLatestV3(store, facts.period().current())).thenReturn(Optional.of(saved));
        checkpoint(SellerWeeklyHistoricalIdentity.sourceHash(facts), id, "REUSED");
        when(revisions.read(store)).thenReturn(4L);
        when(metadata.load(store, WEEK, NOW)).thenReturn(facts);
    }

    @Test
    void oldExactWeekIsCurrentAcrossLocalDaysWithoutEmbeddedIdentityRewrite() {
        // The checkpoint is newer than the immutable snapshot's identity after semantic reuse.
        when(report.sourceIdentityHash()).thenReturn("c".repeat(64));
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        Instant tomorrow = NOW.plusSeconds(86400);
        when(clock.instant()).thenReturn(tomorrow);
        when(metadata.load(store, WEEK, tomorrow)).thenReturn(facts);
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
    }

    @Test
    void noHistoricalSnapshotNeverFallsBackToCurrentRanking() {
        when(report.membership()).thenReturn(new WeeklyReviewV3Response.Membership(
                "CURRENT_RANKING_AT_GENERATION", "a".repeat(64), "a".repeat(64), "a".repeat(64), NOW, 1));
        var result = reads.assess(store, START);
        assertThat(result.state()).isEqualTo(SellerWeeklyV3ReadResult.State.PREPARING);
        assertThat(result.snapshot()).isEmpty();
        verifyNoInteractions(metadata, revisions);
    }

    @Test
    void missingCheckpointAndSupersededCheckpointAreStaleWithoutMetadataWork() {
        when(snapshots.findV3GenerationState(store, facts.period().current())).thenReturn(Optional.empty());
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        checkpoint(SellerWeeklyHistoricalIdentity.sourceHash(facts), UUID.randomUUID(), "REUSED");
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        verifyNoInteractions(metadata);
    }

    @Test
    void globalRevisionAndCanonicalMembershipChangesCannotBeCurrent() {
        when(revisions.read(store)).thenReturn(5L);
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        verifyNoInteractions(metadata);
        when(revisions.read(store)).thenReturn(4L);
        checkpoint("f".repeat(64), id, "CREATED");
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
    }

    @Test
    void missingBaselineCoverageOrStableSourcesKeepsImmutableReportStale() {
        for (String reason : List.of("HISTORY_BASELINE_UNAVAILABLE", "SOURCE_COVERAGE_INCOMPLETE",
                "SOURCE_NOT_STABLE")) {
            doThrow(new SellerHistoricalFactsUnavailableException(reason)).when(metadata).load(store, WEEK, NOW);
            var result = reads.assess(store, START);
            assertThat(result.state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
            assertThat(result.snapshot()).contains(saved);
        }
    }

    @Test
    void inactiveStoreOrChangedTimezoneCannotBeCurrent() throws Exception {
        when(row.getBoolean("is_active")).thenReturn(false);
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        when(row.getBoolean("is_active")).thenReturn(true);
        when(row.getString("timezone")).thenReturn("Europe/Kaliningrad");
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        verifyNoInteractions(metadata);
    }

    @Test
    void futureActionsFromAFormerlyLatestWeekRequireAFreeRevision() {
        var action = mock(WeeklyReviewResponse.Action.class);
        when(report.actions()).thenReturn(List.of(action));
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
    }

    @Test
    void weekBoundaryDuringMetadataReadCannotKeepFutureActionsCurrent() {
        Instant start = Instant.parse("2026-09-21T00:00:00Z");
        Instant followingWeek = start.plusSeconds(7 * 86400);
        when(clock.instant()).thenReturn(start, followingWeek);
        when(metadata.load(store, WEEK, start)).thenReturn(facts);
        when(snapshots.findV3GenerationState(store, facts.period().current())).thenReturn(Optional.of(
                new WeeklyReviewSnapshotStore.V3GenerationState(SellerWeeklyHistoricalIdentity.sourceHash(facts),
                        4, id, start.minusSeconds(1), "CREATED", id)));
        var action = mock(WeeklyReviewResponse.Action.class);
        when(report.actions()).thenReturn(List.of(action));
        assertThat(reads.assess(store, START).state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
    }

    @Test
    void missingStoreAndInvalidOrOpenWeeksFailWithoutSourceWork() {
        assertThatThrownBy(() -> reads.assess(store, START.plusDays(1))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> reads.assess(store, LocalDate.parse("2026-10-05")))
                .isInstanceOf(InvalidRequestException.class);
        when(jdbc.query(eq("SELECT timezone, is_active FROM stores WHERE id=?"), any(RowMapper.class), eq(store)))
                .thenReturn(List.of());
        assertThatThrownBy(() -> reads.assess(store, START)).isInstanceOf(StoreNotFoundException.class);
        verifyNoInteractions(metadata, revisions);
    }

    private void checkpoint(String hash, UUID latest, String outcome) {
        when(snapshots.findV3GenerationState(store, facts.period().current())).thenReturn(Optional.of(
                new WeeklyReviewSnapshotStore.V3GenerationState(
                        hash, 4, id, NOW.minusSeconds(86400), outcome, latest)));
    }
}
