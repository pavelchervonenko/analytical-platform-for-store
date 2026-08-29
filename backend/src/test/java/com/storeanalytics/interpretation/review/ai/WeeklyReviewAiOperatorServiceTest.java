package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewSnapshotStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiOperatorServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final String MODEL = "gpt://folder/yandexgpt-5.1";

    private final WeeklyReviewSnapshotStore snapshotStore = mock(
            WeeklyReviewSnapshotStore.class
    );
    private final WeeklyReviewAiJobStore jobStore = mock(
            WeeklyReviewAiJobStore.class
    );

    @Test
    void rejectsManualGenerationWhileParentFeatureIsDisabled() {
        UUID snapshotId = UUID.randomUUID();

        assertThatThrownBy(() -> service(false).generate(snapshotId))
                .isInstanceOf(WeeklyReviewAiDisabledException.class);

        verify(snapshotStore, never()).findById(snapshotId);
        verify(jobStore, never()).enqueue(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void distinguishesMissingAndIneligibleSnapshots() {
        UUID missing = UUID.randomUUID();
        when(snapshotStore.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(true).generate(missing))
                .isInstanceOf(WeeklyReviewAiSnapshotNotFoundException.class);

        UUID missingJob = UUID.randomUUID();
        when(jobStore.findById(missingJob)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(true).findJob(missingJob))
                .isInstanceOf(WeeklyReviewAiJobNotFoundException.class);

        UUID blocked = UUID.randomUUID();
        PersistedWeeklyReviewSnapshot blockedSnapshot = snapshot(
                blocked, ReportState.BLOCKED
        );
        when(snapshotStore.findById(blocked)).thenReturn(Optional.of(
                blockedSnapshot
        ));
        assertThatThrownBy(() -> service(true).generate(blocked))
                .isInstanceOf(WeeklyReviewAiSnapshotNotEligibleException.class);
    }

    @Test
    void enqueuesExactReadySnapshotIdempotently() {
        UUID snapshotId = UUID.randomUUID();
        PersistedWeeklyReviewSnapshot snapshot = snapshot(
                snapshotId, ReportState.READY
        );
        WeeklyReviewAiJob job = job(snapshotId);
        when(snapshotStore.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(jobStore.enqueue(
                snapshotId,
                "YANDEX",
                MODEL,
                2,
                NOW,
                Duration.ofHours(2)
        )).thenReturn(job);
        when(jobStore.findById(job.id())).thenReturn(Optional.of(job));

        WeeklyReviewAiJobView result = service(true).generate(snapshotId);

        assertThat(result.jobId()).isEqualTo(job.id());
        assertThat(result.snapshotId()).isEqualTo(snapshotId);
        assertThat(result.status()).isEqualTo(WeeklyReviewAiJobStatus.PENDING);
        assertThat(service(true).findJob(job.id()).jobId()).isEqualTo(job.id());
        verify(jobStore).enqueue(
                snapshotId,
                "YANDEX",
                MODEL,
                2,
                NOW,
                Duration.ofHours(2)
        );
    }

    private WeeklyReviewAiOperatorService service(boolean enabled) {
        return new WeeklyReviewAiOperatorService(
                WeeklyReviewAiTestProperties.properties(enabled, false, false),
                snapshotStore,
                jobStore,
                new YandexLlmProperties(
                        "folder",
                        "secret",
                        MODEL,
                        Duration.ofSeconds(5),
                        Duration.ofMinutes(3)
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private PersistedWeeklyReviewSnapshot snapshot(
            UUID snapshotId,
            ReportState state
    ) {
        WeeklyReviewResponse response = mock(WeeklyReviewResponse.class);
        when(response.reportState()).thenReturn(state);
        return new PersistedWeeklyReviewSnapshot(
                snapshotId,
                UUID.randomUUID(),
                1,
                null,
                response,
                "a".repeat(64),
                NOW.minusSeconds(60)
        );
    }

    private WeeklyReviewAiJob job(UUID snapshotId) {
        return new WeeklyReviewAiJob(
                UUID.randomUUID(),
                snapshotId,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                "YANDEX",
                MODEL,
                WeeklyReviewAiJobStatus.PENDING,
                0,
                2,
                NOW,
                NOW.plus(Duration.ofHours(2)),
                null,
                null,
                null,
                null,
                List.of(),
                NOW,
                NOW
        );
    }
}
