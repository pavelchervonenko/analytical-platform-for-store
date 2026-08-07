package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WeeklySnapshotJobExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T04:00:00Z");
    private static final Instant SYNC_COMPLETED_AT = NOW.minusSeconds(120);
    private static final String OWNER = "snapshot-worker-test";
    private static final Versions VERSIONS = WeeklySnapshotPolicyV1.VERSIONS;

    private final WeeklyAnalyticsFactsSource factsSource = mock(
            WeeklyAnalyticsFactsSource.class
    );
    private final WeeklySnapshotDraftBuilder draftBuilder = mock(
            WeeklySnapshotDraftBuilder.class
    );
    private final WeeklySnapshotStore snapshotStore = mock(WeeklySnapshotStore.class);
    private final WeeklySnapshotJobStore jobStore = mock(WeeklySnapshotJobStore.class);
    private final WeeklySnapshotSourceSyncReader sourceSyncReader = mock(
            WeeklySnapshotSourceSyncReader.class
    );
    private final WeeklySnapshotJobControlStore controlStore = mock(
            WeeklySnapshotJobControlStore.class
    );
    private final WeeklySnapshotJobExecutionService service =
            new WeeklySnapshotJobExecutionService(
                    factsSource,
                    draftBuilder,
                    snapshotStore,
                    jobStore,
                    sourceSyncReader,
                    controlStore,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @Test
    void executesClaimedJobFromFactsThroughTerminalSuccess() {
        WeeklySnapshotJob job = job(WeeklySnapshotJobType.AUTO_REVISION);
        WeeklyAnalyticsFactsQuery expectedQuery = query(job);
        WeeklyAnalyticsFacts facts = mock(WeeklyAnalyticsFacts.class);
        WeeklySnapshotDraft draft = mock(WeeklySnapshotDraft.class);
        WeeklySnapshotWriteResult writeResult = mock(WeeklySnapshotWriteResult.class);
        WeeklySnapshotJob completed = mock(WeeklySnapshotJob.class);
        when(factsSource.load(expectedQuery)).thenReturn(facts);
        when(draftBuilder.build(facts, job.timezone())).thenReturn(draft);
        when(draft.storeId()).thenReturn(job.storeId());
        when(draft.query()).thenReturn(expectedQuery);
        when(draft.versions()).thenReturn(job.versions());
        when(sourceSyncReader.completedAt(job.storeId(), job.sourceSyncJobId()))
                .thenReturn(SYNC_COMPLETED_AT);
        when(snapshotStore.persist(any(WeeklySnapshotPersistenceCommand.class)))
                .thenReturn(writeResult);
        when(jobStore.complete(job.id(), OWNER, writeResult, NOW)).thenReturn(completed);

        WeeklySnapshotJob result = service.execute(job, OWNER);

        assertThat(result).isSameAs(completed);
        ArgumentCaptor<WeeklySnapshotPersistenceCommand> command =
                ArgumentCaptor.forClass(WeeklySnapshotPersistenceCommand.class);
        verify(snapshotStore).persist(command.capture());
        assertThat(command.getValue().draft()).isSameAs(draft);
        assertThat(command.getValue().sourceSyncCompletedAt())
                .isEqualTo(SYNC_COMPLETED_AT);
        assertThat(command.getValue().sourceDataCutoff())
                .isEqualTo(job.sourceDataCutoff());
        assertThat(command.getValue().revisionReason())
                .isEqualTo(WeeklySnapshotRevisionReason.AUTO_REVISION);
    }

    @Test
    void rejectsDraftBuiltWithDifferentContractVersionsBeforePersistence() {
        WeeklySnapshotJob job = job(WeeklySnapshotJobType.INITIAL);
        WeeklyAnalyticsFacts facts = mock(WeeklyAnalyticsFacts.class);
        WeeklySnapshotDraft draft = mock(WeeklySnapshotDraft.class);
        when(factsSource.load(query(job))).thenReturn(facts);
        when(draftBuilder.build(facts, job.timezone())).thenReturn(draft);
        when(draft.storeId()).thenReturn(job.storeId());
        when(draft.query()).thenReturn(query(job));
        when(draft.versions()).thenReturn(new Versions(
                VERSIONS.factsSchemaVersion() + 1,
                VERSIONS.metricContractVersion(),
                VERSIONS.calculationVersion(),
                VERSIONS.qualityPolicyVersion()
        ));

        assertThatThrownBy(() -> service.execute(job, OWNER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versions differ");
        verify(sourceSyncReader, never()).completedAt(any(), any());
        verify(snapshotStore, never()).persist(any());
        verify(jobStore, never()).complete(any(), any(), any(), any());
    }

    @Test
    void stopsAtCheckpointWhenCancellationArrivesDuringFactsLoad() {
        WeeklySnapshotJob job = job(WeeklySnapshotJobType.INITIAL);
        WeeklyAnalyticsFacts facts = mock(WeeklyAnalyticsFacts.class);
        when(controlStore.cancellationRequested(job.id())).thenReturn(false, true);
        when(factsSource.load(query(job))).thenReturn(facts);

        assertThatThrownBy(() -> service.execute(job, OWNER))
                .isInstanceOf(WeeklySnapshotJobCancellationException.class);

        verify(factsSource).load(query(job));
        verify(draftBuilder, never()).build(any(), any());
        verify(sourceSyncReader, never()).completedAt(any(), any());
        verify(snapshotStore, never()).persist(any());
        verify(jobStore, never()).complete(any(), any(), any(), any());
    }

    private WeeklySnapshotJob job(WeeklySnapshotJobType type) {
        UUID baseSnapshotId = type == WeeklySnapshotJobType.AUTO_REVISION
                ? UUID.randomUUID() : null;
        return new WeeklySnapshotJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                type,
                new StoreKpiPeriod(
                        LocalDate.of(2026, 7, 20),
                        LocalDate.of(2026, 7, 26)
                ),
                "Europe/Moscow",
                UUID.randomUUID(),
                NOW,
                VERSIONS,
                baseSnapshotId,
                WeeklySnapshotJobStatus.RUNNING,
                null,
                null,
                1,
                3,
                NOW.minusSeconds(10),
                OWNER,
                NOW.plusSeconds(300),
                false,
                null,
                null,
                NOW.minusSeconds(5),
                null,
                1,
                NOW.minusSeconds(10),
                NOW.minusSeconds(5)
        );
    }

    private WeeklyAnalyticsFactsQuery query(WeeklySnapshotJob job) {
        return new WeeklyAnalyticsFactsQuery(
                job.storeId(),
                job.period(),
                new StoreKpiPeriod(
                        job.period().start().minusDays(7),
                        job.period().end().minusDays(7)
                )
        );
    }
}
