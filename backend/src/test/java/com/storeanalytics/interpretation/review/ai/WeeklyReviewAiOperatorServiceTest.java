package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.common.exception.PreconditionRequiredException;
import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.integration.llm.yandex.YandexLlmRequestPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import com.storeanalytics.interpretation.review.PersistedWeeklyReviewSnapshot;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.ReportState;
import com.storeanalytics.interpretation.review.WeeklyReviewSnapshotStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiOperatorServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final String MODEL = "gpt://folder/yandexgpt-5.1";
    private static final String SNAPSHOT_HASH = "a".repeat(64);
    private static final String INPUT_HASH = "b".repeat(64);
    private static final String REQUEST_HASH = "c".repeat(64);
    private static final BigDecimal ESTIMATED_COST = new BigDecimal("2.500000");

    private final WeeklyReviewSnapshotStore snapshotStore = mock(
            WeeklyReviewSnapshotStore.class
    );
    private final WeeklyReviewAiJobStore jobStore = mock(
            WeeklyReviewAiJobStore.class
    );
    private final WeeklyReviewAiEnrichmentStore enrichmentStore = mock(
            WeeklyReviewAiEnrichmentStore.class
    );
    private final WeeklyReviewAiProviderRequestFactory requestFactory = mock(
            WeeklyReviewAiProviderRequestFactory.class
    );
    private final WeeklyReviewAiBudgetGuard budgetGuard = mock(
            WeeklyReviewAiBudgetGuard.class
    );
    private final YandexLlmRequestPreflight requestPreflight = mock(
            YandexLlmRequestPreflight.class
    );

    @BeforeEach
    void configurePreflight() {
        PreparedWeeklyReviewAiRequest prepared = mock(
                PreparedWeeklyReviewAiRequest.class
        );
        WeeklyReviewAiInput input = mock(WeeklyReviewAiInput.class);
        LlmProviderRequest providerRequest = mock(LlmProviderRequest.class);
        when(input.factors()).thenReturn(List.of());
        when(input.actions()).thenReturn(List.of());
        when(input.evidence()).thenReturn(List.of());
        when(prepared.input()).thenReturn(input);
        when(prepared.inputHash()).thenReturn(INPUT_HASH);
        when(prepared.requestHash()).thenReturn(REQUEST_HASH);
        when(prepared.request()).thenReturn(providerRequest);
        when(requestFactory.prepare(any())).thenReturn(prepared);
        when(requestPreflight.evaluate(providerRequest)).thenReturn(
                new LlmProviderPreflight(
                        1000, 32_768, ESTIMATED_COST, "RUB"
                )
        );
        when(jobStore.actualCostSince(any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void rejectsManualGenerationWhileParentFeatureIsDisabled() {
        UUID snapshotId = UUID.randomUUID();

        assertThatThrownBy(() -> service(false, false).generate(
                snapshotId, approval(1)
        )).isInstanceOf(WeeklyReviewAiDisabledException.class);

        verify(snapshotStore, never()).findById(snapshotId);
        verify(jobStore, never()).enqueueApproved(
                any(), any(), any(), anyInt(), any(), any()
        );
    }

    @Test
    void rejectsManualGenerationWhileWorkerIsDisabled() {
        UUID snapshotId = UUID.randomUUID();

        assertThatThrownBy(() -> service(true, false).generate(
                snapshotId, approval(1)
        )).isInstanceOf(WeeklyReviewAiWorkerDisabledException.class);

        verify(snapshotStore, never()).findById(snapshotId);
        verify(jobStore, never()).enqueueApproved(
                any(), any(), any(), anyInt(), any(), any()
        );
    }

    @Test
    void distinguishesMissingAndIneligibleSnapshots() {
        UUID missing = UUID.randomUUID();
        when(snapshotStore.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(true, true).preflight(missing))
                .isInstanceOf(WeeklyReviewAiSnapshotNotFoundException.class);

        UUID missingJob = UUID.randomUUID();
        when(jobStore.findById(missingJob)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(true, true).findJob(missingJob))
                .isInstanceOf(WeeklyReviewAiJobNotFoundException.class);

        UUID blocked = UUID.randomUUID();
        PersistedWeeklyReviewSnapshot blockedSnapshot = snapshot(
                blocked, ReportState.BLOCKED
        );
        when(snapshotStore.findById(blocked)).thenReturn(Optional.of(
                blockedSnapshot
        ));
        assertThatThrownBy(() -> service(true, true).preflight(blocked))
                .isInstanceOf(
                        WeeklyReviewAiSnapshotNotEligibleException.class
                );
    }

    @Test
    void returnsSanitizedStablePreflightWithoutEnqueue() {
        UUID snapshotId = UUID.randomUUID();
        PersistedWeeklyReviewSnapshot snapshot = snapshot(
                snapshotId, ReportState.READY
        );
        when(snapshotStore.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(jobStore.findBySnapshot(snapshotId)).thenReturn(Optional.empty());
        when(enrichmentStore.findActive(snapshotId)).thenReturn(
                Optional.empty()
        );

        WeeklyReviewAiPreflightView first = service(false, false).preflight(
                snapshotId
        );
        WeeklyReviewAiPreflightView second = service(false, false).preflight(
                snapshotId
        );

        assertThat(first.request().inputHash()).isEqualTo(INPUT_HASH);
        assertThat(first.request().requestHash()).isEqualTo(REQUEST_HASH);
        assertThat(second.request().requestHash()).isEqualTo(REQUEST_HASH);
        assertThat(first.snapshot().contentHash()).isEqualTo(SNAPSHOT_HASH);
        assertThat(first.snapshot().periodStart()).isEqualTo(
                LocalDate.parse("2026-08-17")
        );
        assertThat(first.privacy().verdict())
                .isEqualTo("PASS_STORE_ONLY_SCHEMA");
        assertThat(first.privacy().employeeScopeIncluded()).isFalse();
        assertThat(first.privacy().rawInputIncluded()).isFalse();
        assertThat(first.request().modelVersion()).isEqualTo("yandexgpt-5.1");
        assertThat(first.request().providerCredentialCheck())
                .isEqualTo("WORKER_ONLY");
        assertThat(first.budget().estimatedMaximumCostPerCall())
                .isEqualByComparingTo(ESTIMATED_COST);
        assertThat(first.budget().estimatedMaximumCostAtAllowedCalls())
                .isEqualByComparingTo("5.000000");
        assertThat(first.approvalEligible()).isTrue();
        assertThat(first.generationEnabled()).isFalse();
        verify(jobStore, never()).enqueueApproved(
                any(), any(), any(), anyInt(), any(), any()
        );
    }

    @Test
    void requiresExactApprovalAndEnqueuesWithApprovedSingleCall() {
        UUID snapshotId = UUID.randomUUID();
        PersistedWeeklyReviewSnapshot snapshot = snapshot(
                snapshotId, ReportState.PARTIAL
        );
        WeeklyReviewAiJob job = job(snapshotId, 1);
        when(snapshotStore.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(jobStore.findBySnapshot(snapshotId)).thenReturn(Optional.empty());
        when(enrichmentStore.findActive(snapshotId)).thenReturn(
                Optional.empty()
        );
        when(jobStore.enqueueApproved(
                snapshotId, "YANDEX", MODEL, 1, NOW, Duration.ofHours(2)
        )).thenReturn(job);

        WeeklyReviewAiJobView result = service(true, true).generate(
                snapshotId, approval(1)
        );

        assertThat(result.jobId()).isEqualTo(job.id());
        assertThat(result.maxAttempts()).isEqualTo(1);
        verify(jobStore).enqueueApproved(
                snapshotId, "YANDEX", MODEL, 1, NOW, Duration.ofHours(2)
        );
    }

    @Test
    void rejectsMissingStaleOrConflictingApprovalWithoutEnqueue() {
        UUID snapshotId = UUID.randomUUID();
        PersistedWeeklyReviewSnapshot snapshot = snapshot(
                snapshotId, ReportState.READY
        );
        when(snapshotStore.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(jobStore.findBySnapshot(snapshotId)).thenReturn(Optional.empty());
        when(enrichmentStore.findActive(snapshotId)).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() -> service(true, true).generate(
                snapshotId, null
        )).isInstanceOf(PreconditionRequiredException.class);
        WeeklyReviewAiGenerationApproval stale = new WeeklyReviewAiGenerationApproval(
                "d".repeat(64), INPUT_HASH, REQUEST_HASH, 1,
                ESTIMATED_COST, "RUB"
        );
        assertThatThrownBy(() -> service(true, true).generate(
                snapshotId, stale
        )).isInstanceOf(PreconditionFailedException.class);

        when(jobStore.findBySnapshot(snapshotId)).thenReturn(Optional.of(
                job(snapshotId, 1)
        ));
        assertThatThrownBy(() -> service(true, true).generate(
                snapshotId, approval(1)
        )).isInstanceOf(PreconditionFailedException.class);
        verify(jobStore, never()).enqueueApproved(
                any(), any(), any(), anyInt(), any(), any()
        );
    }

    @Test
    void convertsPrivacyOrRequestPreparationFailureToSafeRejection() {
        UUID snapshotId = UUID.randomUUID();
        PersistedWeeklyReviewSnapshot snapshot = snapshot(
                snapshotId, ReportState.READY
        );
        when(snapshotStore.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(requestFactory.prepare(any())).thenThrow(
                new IllegalArgumentException("employee name must not leave")
        );

        assertThatThrownBy(() -> service(false, false).preflight(snapshotId))
                .isInstanceOf(WeeklyReviewAiPreflightRejectedException.class)
                .hasMessageNotContaining("employee name");
    }

    private WeeklyReviewAiOperatorService service(
            boolean enabled,
            boolean workerEnabled
    ) {
        return new WeeklyReviewAiOperatorService(
                WeeklyReviewAiTestProperties.properties(
                        enabled, false, workerEnabled
                ),
                snapshotStore,
                jobStore,
                new WeeklyReviewAiOperatorSupport(
                        enrichmentStore,
                        requestFactory,
                        budgetGuard,
                        requestPreflight,
                        new YandexLlmProperties(
                                "folder",
                                "",
                                MODEL,
                                Duration.ofSeconds(5),
                                Duration.ofMinutes(3)
                        )
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private WeeklyReviewAiGenerationApproval approval(int maximumCalls) {
        return new WeeklyReviewAiGenerationApproval(
                SNAPSHOT_HASH,
                INPUT_HASH,
                REQUEST_HASH,
                maximumCalls,
                ESTIMATED_COST.multiply(BigDecimal.valueOf(maximumCalls)),
                "RUB"
        );
    }

    private PersistedWeeklyReviewSnapshot snapshot(
            UUID snapshotId,
            ReportState state
    ) {
        WeeklyReviewResponse response = mock(WeeklyReviewResponse.class);
        when(response.reportState()).thenReturn(state);
        when(response.period()).thenReturn(new PeriodContext(
                "Europe/Kaliningrad",
                new DateRange(
                        LocalDate.parse("2026-08-17"),
                        LocalDate.parse("2026-08-23")
                ),
                new DateRange(
                        LocalDate.parse("2026-08-10"),
                        LocalDate.parse("2026-08-16")
                ),
                "17–23 августа",
                "10–16 августа"
        ));
        return new PersistedWeeklyReviewSnapshot(
                snapshotId,
                UUID.randomUUID(),
                1,
                null,
                response,
                SNAPSHOT_HASH,
                NOW.minusSeconds(60)
        );
    }

    private WeeklyReviewAiJob job(UUID snapshotId, int maxAttempts) {
        return new WeeklyReviewAiJob(
                UUID.randomUUID(),
                snapshotId,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                "YANDEX",
                MODEL,
                WeeklyReviewAiJobStatus.PENDING,
                0,
                maxAttempts,
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
