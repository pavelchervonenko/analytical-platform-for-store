package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.config.WeeklySnapshotPlannerProperties;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.LatestSnapshot;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.SourceSync;
import com.storeanalytics.interpretation.snapshot.WeeklySnapshotPlanningStore.StoreTarget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WeeklySnapshotPlanningServiceTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final String TIMEZONE = "Europe/Kaliningrad";
    private static final Instant MONDAY_NOW = Instant.parse("2026-08-03T02:00:00Z");
    private static final Instant REQUIRED_COVERAGE = Instant.parse(
            "2026-08-02T22:00:00Z"
    );

    private final WeeklySnapshotPlanningStore planningStore = mock(
            WeeklySnapshotPlanningStore.class
    );
    private final WeeklySnapshotJobStore jobStore = mock(WeeklySnapshotJobStore.class);

    @Test
    void createsInitialRequestOnlyFromSyncCoveringTheCompletedWeek() {
        SourceSync source = source(SOURCE_ID, MONDAY_NOW.minusSeconds(60));
        WeeklySnapshotPlanningService service = service(MONDAY_NOW);
        when(planningStore.activeStores(25)).thenReturn(List.of(store()));
        when(planningStore.newestSuitableSource(
                STORE_ID, REQUIRED_COVERAGE, MONDAY_NOW
        )).thenReturn(Optional.of(source));
        when(planningStore.latestSnapshot(
                STORE_ID,
                java.time.LocalDate.of(2026, 7, 27),
                java.time.LocalDate.of(2026, 8, 2)
        )).thenReturn(Optional.empty());

        WeeklySnapshotPlanningResult result = service.plan();

        ArgumentCaptor<WeeklySnapshotJobRequest> request = ArgumentCaptor.forClass(
                WeeklySnapshotJobRequest.class
        );
        verify(jobStore).enqueue(request.capture(), eq(MONDAY_NOW));
        assertThat(request.getValue().jobType()).isEqualTo(WeeklySnapshotJobType.INITIAL);
        assertThat(request.getValue().period().start())
                .isEqualTo(java.time.LocalDate.of(2026, 7, 27));
        assertThat(request.getValue().period().end())
                .isEqualTo(java.time.LocalDate.of(2026, 8, 2));
        assertThat(request.getValue().sourceDataCutoff()).isEqualTo(source.completedAt());
        assertThat(request.getValue().baseSnapshotId()).isNull();
        assertThat(result.requestsAccepted()).isOne();
    }

    @Test
    void createsAutoRevisionInsideWindowForNewerSourceCutoff() {
        Instant tuesdayNow = Instant.parse("2026-08-04T02:00:00Z");
        UUID baseId = UUID.randomUUID();
        SourceSync source = source(SOURCE_ID, tuesdayNow.minusSeconds(60));
        when(planningStore.activeStores(25)).thenReturn(List.of(store()));
        when(planningStore.newestSuitableSource(
                STORE_ID, REQUIRED_COVERAGE, tuesdayNow
        )).thenReturn(Optional.of(source));
        when(planningStore.latestSnapshot(any(), any(), any())).thenReturn(Optional.of(
                new LatestSnapshot(
                        baseId,
                        UUID.randomUUID(),
                        MONDAY_NOW.minusSeconds(120),
                        MONDAY_NOW.minusSeconds(120)
                )
        ));

        WeeklySnapshotPlanningResult result = service(tuesdayNow).plan();

        ArgumentCaptor<WeeklySnapshotJobRequest> request = ArgumentCaptor.forClass(
                WeeklySnapshotJobRequest.class
        );
        verify(jobStore).enqueue(request.capture(), eq(tuesdayNow));
        assertThat(request.getValue().jobType())
                .isEqualTo(WeeklySnapshotJobType.AUTO_REVISION);
        assertThat(request.getValue().baseSnapshotId()).isEqualTo(baseId);
        assertThat(result.requestsAccepted()).isOne();
    }

    @Test
    void doesNotReviseAfterWindowOrWithoutNewSourceCutoff() {
        Instant afterWindow = Instant.parse("2026-08-06T02:00:00Z");
        SourceSync source = source(SOURCE_ID, afterWindow.minusSeconds(60));
        when(planningStore.activeStores(25)).thenReturn(List.of(store()));
        when(planningStore.newestSuitableSource(
                STORE_ID, REQUIRED_COVERAGE, afterWindow
        )).thenReturn(Optional.of(source));
        when(planningStore.latestSnapshot(any(), any(), any())).thenReturn(Optional.of(
                latest(MONDAY_NOW)
        ));

        WeeklySnapshotPlanningResult closed = service(afterWindow).plan();

        verify(jobStore, never()).enqueue(any(), any());
        assertThat(closed.revisionWindowClosed()).isOne();

        org.mockito.Mockito.reset(planningStore, jobStore);
        when(planningStore.activeStores(25)).thenReturn(List.of(store()));
        when(planningStore.newestSuitableSource(
                STORE_ID, REQUIRED_COVERAGE, MONDAY_NOW
        )).thenReturn(Optional.of(source(SOURCE_ID, MONDAY_NOW.minusSeconds(60))));
        when(planningStore.latestSnapshot(any(), any(), any())).thenReturn(Optional.of(
                latest(MONDAY_NOW)
        ));

        WeeklySnapshotPlanningResult unchanged = service(MONDAY_NOW).plan();

        verify(jobStore, never()).enqueue(any(), any());
        assertThat(unchanged.sourceUnchanged()).isOne();
    }

    @Test
    void skipsAlreadyPlannedRequestIdempotently() {
        SourceSync source = source(SOURCE_ID, MONDAY_NOW.minusSeconds(60));
        when(planningStore.activeStores(25)).thenReturn(List.of(store()));
        when(planningStore.newestSuitableSource(
                STORE_ID, REQUIRED_COVERAGE, MONDAY_NOW
        )).thenReturn(Optional.of(source));
        when(planningStore.latestSnapshot(any(), any(), any())).thenReturn(Optional.empty());
        when(jobStore.requestExists(any())).thenReturn(true);

        WeeklySnapshotPlanningResult result = service(MONDAY_NOW).plan();

        verify(jobStore, never()).enqueue(any(), any());
        assertThat(result.alreadyPlanned()).isOne();
    }

    private WeeklySnapshotPlanningService service(Instant now) {
        return new WeeklySnapshotPlanningService(
                planningStore,
                jobStore,
                new WeeklySnapshotPlannerProperties(
                        true,
                        Duration.ofMinutes(1),
                        Duration.ofHours(72),
                        25,
                        5
                ),
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    private StoreTarget store() {
        return new StoreTarget(STORE_ID, TIMEZONE);
    }

    private SourceSync source(UUID id, Instant completedAt) {
        return new SourceSync(id, REQUIRED_COVERAGE, completedAt);
    }

    private LatestSnapshot latest(Instant cutoff) {
        return new LatestSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                cutoff,
                cutoff
        );
    }
}
