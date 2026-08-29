package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiEnhancement;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.AiState;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiStateResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Test
    void keepsDisabledAndBlockedStatesIndependentFromJobs() {
        assertThat(resolve(false, false, ReportState.READY, Optional.empty()).state())
                .isEqualTo(AiState.DISABLED);
        assertThat(resolve(true, true, ReportState.BLOCKED, Optional.empty()).state())
                .isEqualTo(AiState.NOT_APPLICABLE);
    }

    @Test
    void mapsLifecycleWithoutHidingDeterministicReport() {
        assertThat(resolve(true, true, ReportState.READY, Optional.empty()).state())
                .isEqualTo(AiState.PREPARING);
        assertThat(resolve(true, false, ReportState.READY, Optional.empty()).state())
                .isEqualTo(AiState.UNAVAILABLE);
        assertThat(resolve(true, true, ReportState.READY,
                Optional.of(job(WeeklyReviewAiJobStatus.RUNNING, NOW.minusSeconds(60))))
                .state()).isEqualTo(AiState.PREPARING);
        assertThat(resolve(true, true, ReportState.READY,
                Optional.of(job(WeeklyReviewAiJobStatus.RETRY_WAIT,
                        NOW.minusSeconds(600)))).state()).isEqualTo(AiState.DELAYED);
        assertThat(resolve(true, true, ReportState.READY,
                Optional.of(job(WeeklyReviewAiJobStatus.FAILED,
                        NOW.minusSeconds(60)))).state()).isEqualTo(AiState.UNAVAILABLE);
    }

    private AiEnhancement resolve(
            boolean enabled,
            boolean plannerEnabled,
            ReportState reportState,
            Optional<WeeklyReviewAiJob> job
    ) {
        WeeklyReviewAiJobStore store = mock(WeeklyReviewAiJobStore.class);
        UUID snapshotId = UUID.randomUUID();
        when(store.findBySnapshot(snapshotId)).thenReturn(job);
        WeeklyReviewResponse response = mock(WeeklyReviewResponse.class);
        when(response.reportState()).thenReturn(reportState);
        AtomicReference<AiEnhancement> captured = new AtomicReference<>();
        when(response.withAiEnhancement(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return response;
        });
        PersistedWeeklyReviewSnapshot snapshot = new PersistedWeeklyReviewSnapshot(
                snapshotId, UUID.randomUUID(), 1, null, response,
                "a".repeat(64), NOW.minusSeconds(60)
        );
        WeeklyReviewAiStateResolver resolver = new WeeklyReviewAiStateResolver(
                WeeklyReviewAiTestProperties.properties(enabled, plannerEnabled, false),
                store
        );

        assertThat(resolver.apply(snapshot, NOW)).isSameAs(response);
        return captured.get();
    }

    private WeeklyReviewAiJob job(
            WeeklyReviewAiJobStatus status,
            Instant createdAt
    ) {
        boolean running = status == WeeklyReviewAiJobStatus.RUNNING;
        return new WeeklyReviewAiJob(
                UUID.randomUUID(), UUID.randomUUID(),
                WeeklyReviewAiContract.PROMPT_VERSION, 4,
                "YANDEX", "gpt://folder/yandexgpt-5.1", status,
                running ? 1 : 0, 2, NOW, NOW.plusSeconds(3600),
                running ? "worker" : null,
                running ? NOW.plusSeconds(60) : null,
                null, null, List.of(), createdAt, createdAt
        );
    }
}
