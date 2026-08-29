package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.SourceSync;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.StoreTarget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeeklyReviewSnapshotPlanningServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
    private static final UUID STORE_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );
    private static final StoreTarget STORE = new StoreTarget(
            STORE_ID, "Europe/Kaliningrad"
    );

    private WeeklySnapshotPlanningStore sourceStore;
    private WeeklyReviewSnapshotStore snapshotStore;
    private WeeklyReviewService reviewService;
    private WeeklyReviewSnapshotPlanningService service;

    @BeforeEach
    void setUp() {
        sourceStore = mock(WeeklySnapshotPlanningStore.class);
        snapshotStore = mock(WeeklyReviewSnapshotStore.class);
        reviewService = mock(WeeklyReviewService.class);
        service = service(25);
        when(sourceStore.activeStoresAfter(null, 25)).thenReturn(List.of(STORE));
    }

    @Test
    void createsFirstSnapshotOnlyAfterSourceCoversBothComparedWeeks() {
        when(sourceStore.newestSuitableSource(
                eq(STORE_ID), any(), any(), eq(NOW)
        )).thenReturn(Optional.of(source("2026-08-24T03:00:00Z")));
        when(snapshotStore.findLatest(eq(STORE_ID), any(DateRange.class)))
                .thenReturn(Optional.empty());
        PersistedWeeklyReviewSnapshot generated = snapshot(
                UUID.randomUUID(), Instant.parse("2026-08-24T03:00:00Z")
        );
        when(reviewService.generate(STORE_ID)).thenReturn(generated);

        WeeklyReviewSnapshotPlanningResult result = service.plan();

        assertThat(result).isEqualTo(new WeeklyReviewSnapshotPlanningResult(
                1, 1, 0, 0, 0, 0, 0, 0
        ));
        verify(sourceStore).newestSuitableSource(
                STORE_ID,
                Instant.parse("2026-08-09T22:00:00Z"),
                Instant.parse("2026-08-23T22:00:00Z"),
                NOW
        );
        verify(reviewService).generate(STORE_ID);
    }

    @Test
    void skipsGenerationWhenCoverageIsMissingOrTheSourceWasAlreadyApplied() {
        when(sourceStore.newestSuitableSource(
                eq(STORE_ID), any(), any(), eq(NOW)
        )).thenReturn(Optional.empty());

        assertThat(service.plan().sourceUnavailable()).isOne();
        verify(reviewService, never()).generate(any());

        Instant completedAt = Instant.parse("2026-08-24T03:00:00Z");
        when(sourceStore.newestSuitableSource(
                eq(STORE_ID), any(), any(), eq(NOW)
        )).thenReturn(Optional.of(source(completedAt.toString())));
        PersistedWeeklyReviewSnapshot existing = snapshot(
                UUID.randomUUID(), completedAt
        );
        when(snapshotStore.findLatest(eq(STORE_ID), any(DateRange.class)))
                .thenReturn(Optional.of(existing));

        WeeklyReviewSnapshotPlanningResult result = service.plan();

        assertThat(result.sourceUnchanged()).isOne();
        verify(reviewService, never()).generate(any());
    }

    @Test
    void createsARevisionWhenANewerCompletedSourceAppears() {
        UUID previousId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        PersistedWeeklyReviewSnapshot previous = snapshot(
                previousId, Instant.parse("2026-08-24T03:00:00Z")
        );
        PersistedWeeklyReviewSnapshot revision = snapshot(
                revisionId, Instant.parse("2026-08-24T05:00:00Z")
        );
        when(sourceStore.newestSuitableSource(
                eq(STORE_ID), any(), any(), eq(NOW)
        )).thenReturn(Optional.of(source("2026-08-24T05:00:00Z")));
        when(snapshotStore.findLatest(eq(STORE_ID), any(DateRange.class)))
                .thenReturn(Optional.of(previous));
        when(reviewService.generate(STORE_ID)).thenReturn(revision);

        WeeklyReviewSnapshotPlanningResult result = service.plan();

        assertThat(result.revisionsCreated()).isOne();
        assertThat(result.failures()).isZero();
        verify(reviewService).generate(STORE_ID);
    }

    @Test
    void scansEveryKeysetPageInsteadOfRepeatingTheFirstBatch() {
        StoreTarget second = new StoreTarget(
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                "Europe/Kaliningrad"
        );
        StoreTarget third = new StoreTarget(
                UUID.fromString("30000000-0000-0000-0000-000000000003"),
                "Europe/Kaliningrad"
        );
        when(sourceStore.activeStoresAfter(null, 2))
                .thenReturn(List.of(STORE, second));
        when(sourceStore.activeStoresAfter(second.storeId(), 2))
                .thenReturn(List.of(third));
        when(sourceStore.newestSuitableSource(
                any(), any(), any(), eq(NOW)
        )).thenReturn(Optional.empty());

        WeeklyReviewSnapshotPlanningResult result = service(2).plan();

        assertThat(result.storesScanned()).isEqualTo(3);
        assertThat(result.sourceUnavailable()).isEqualTo(3);
        verify(sourceStore).activeStoresAfter(second.storeId(), 2);
    }

    private WeeklyReviewSnapshotPlanningService service(int batchSize) {
        return new WeeklyReviewSnapshotPlanningService(
                sourceStore,
                snapshotStore,
                reviewService,
                new WeeklyReviewSnapshotPlannerProperties(
                        false, Duration.ofMinutes(5), batchSize
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private SourceSync source(String completedAt) {
        Instant timestamp = Instant.parse(completedAt);
        return new SourceSync(UUID.randomUUID(), timestamp, timestamp);
    }

    private PersistedWeeklyReviewSnapshot snapshot(
            UUID id,
            Instant sourceDataUpdatedAt
    ) {
        WeeklyReviewResponse response = mock(WeeklyReviewResponse.class);
        Provenance provenance = mock(Provenance.class);
        when(provenance.sourceDataUpdatedAt()).thenReturn(sourceDataUpdatedAt);
        when(response.provenance()).thenReturn(provenance);
        return new PersistedWeeklyReviewSnapshot(
                id,
                STORE_ID,
                1,
                null,
                response,
                "a".repeat(64),
                NOW.minusSeconds(60)
        );
    }
}
