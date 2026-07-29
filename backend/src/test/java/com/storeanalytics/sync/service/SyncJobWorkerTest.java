package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.config.SyncProperties;
import com.storeanalytics.integration.livesklad.exception.LiveSkladHttpException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException;
import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException.Reason;
import com.storeanalytics.integration.livesklad.exception.LiveSkladRateLimitException;
import com.storeanalytics.sync.exception.SalesSyncCapacityException;
import com.storeanalytics.sync.exception.SalesSyncException;
import com.storeanalytics.sync.model.SyncJobPhase;
import com.storeanalytics.sync.model.SyncJobType;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SyncJobWorkerTest {

    private final SyncJobCoordinator coordinator = mock(SyncJobCoordinator.class);
    private final SyncJobExecutionService executionService =
            mock(SyncJobExecutionService.class);
    private final UUID jobId = UUID.randomUUID();
    private SyncJobWorker worker;
    private SyncJobClaim claim;

    @BeforeEach
    void setUp() {
        SyncProperties properties = new SyncProperties(
                Duration.ofDays(1),
                5,
                Duration.ofHours(2),
                Duration.ofMinutes(1),
                Duration.ofMinutes(15),
                Duration.ofDays(1),
                3,
                730,
                ZoneId.of("Europe/Kaliningrad")
        );
        worker = new SyncJobWorker(coordinator, executionService, properties);
        claim = new SyncJobClaim(
                jobId,
                null,
                SyncJobType.BACKFILL,
                SyncJobPhase.SALES,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                0
        );
    }

    @Test
    void completesSuccessfullyExecutedStep() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));

        worker.processNextStep();

        verify(executionService).execute(claim);
        verify(coordinator).completeStep(eq(jobId), anyString());
    }

    @Test
    void shrinksCapacityLimitedWindowWithoutMarkingRetry() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));
        when(executionService.execute(claim)).thenThrow(
                new SalesSyncCapacityException(UUID.randomUUID(), 71, 70)
        );
        when(coordinator.shrinkWindow(eq(jobId), anyString())).thenReturn(true);

        worker.processNextStep();

        verify(coordinator).shrinkWindow(eq(jobId), anyString());
        verify(coordinator, never()).retryOrFail(
                any(), anyString(), anyString(), anyBoolean(), any()
        );
    }

    @Test
    void honorsSourceRetryWindowWithoutExposingCauseMessage() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));
        when(executionService.execute(claim)).thenThrow(new SalesSyncException(
                UUID.randomUUID(),
                new LiveSkladRateLimitException(
                        "sensitive upstream detail",
                        Duration.ofMinutes(10)
                )
        ));
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> delay = ArgumentCaptor.forClass(Duration.class);

        worker.processNextStep();

        verify(coordinator).retryOrFail(
                eq(jobId),
                anyString(),
                summary.capture(),
                eq(true),
                delay.capture()
        );
        assertThat(summary.getValue())
                .isEqualTo("Synchronization phase SALES failed: LIVESKLAD_RATE_LIMIT")
                .doesNotContain("sensitive");
        assertThat(delay.getValue()).isBetween(
                Duration.ofMinutes(10), Duration.ofMinutes(12)
        );
    }

    @Test
    void doesNotRetryRejectedUpstreamPayload() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));
        when(executionService.execute(claim)).thenThrow(new SalesSyncException(
                UUID.randomUUID(),
                new LiveSkladPayloadRejectedException(
                        Reason.RAW_PAYLOAD_TOO_LARGE,
                        "safe rejection"
                )
        ));

        worker.processNextStep();

        verify(coordinator).retryOrFail(
                eq(jobId),
                anyString(),
                anyString(),
                eq(false),
                any()
        );
    }

    @Test
    void doesNotRetryPermanentUpstreamHttpFailure() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));
        when(executionService.execute(claim)).thenThrow(new SalesSyncException(
                UUID.randomUUID(),
                new LiveSkladHttpException("Fetch sales", 401)
        ));
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);

        worker.processNextStep();

        verify(coordinator).retryOrFail(
                eq(jobId),
                anyString(),
                summary.capture(),
                eq(false),
                any()
        );
        assertThat(summary.getValue())
                .isEqualTo("Synchronization phase SALES failed: "
                        + "LIVESKLAD_HTTP_401")
                .doesNotContain("Fetch sales");
    }

    @Test
    void retriesTransientUpstreamServerFailure() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));
        when(executionService.execute(claim)).thenThrow(new SalesSyncException(
                UUID.randomUUID(),
                new LiveSkladHttpException("Fetch sales", 503)
        ));
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> delay = ArgumentCaptor.forClass(Duration.class);

        worker.processNextStep();

        verify(coordinator).retryOrFail(
                eq(jobId),
                anyString(),
                summary.capture(),
                eq(true),
                delay.capture()
        );
        assertThat(delay.getValue()).isBetween(
                Duration.ofMinutes(1), Duration.ofSeconds(72)
        );
        assertThat(summary.getValue())
                .isEqualTo("Synchronization phase SALES failed: "
                        + "LIVESKLAD_HTTP_503");
    }
    @Test
    void capsExcessiveSourceRetryWindowAtConfiguredAbsoluteMaximum() {
        when(coordinator.claimNext(anyString())).thenReturn(Optional.of(claim));
        when(executionService.execute(claim)).thenThrow(new SalesSyncException(
                UUID.randomUUID(),
                new LiveSkladRateLimitException(
                        "safe upstream rate limit",
                        Duration.ofDays(2)
                )
        ));
        ArgumentCaptor<Duration> delay = ArgumentCaptor.forClass(Duration.class);

        worker.processNextStep();

        verify(coordinator).retryOrFail(
                eq(jobId),
                anyString(),
                anyString(),
                eq(true),
                delay.capture()
        );
        assertThat(delay.getValue()).isEqualTo(Duration.ofDays(1));
    }
}
