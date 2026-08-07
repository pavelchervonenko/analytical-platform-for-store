package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class WeeklySnapshotJobRunnerTest {

    private static final Instant NOW = Instant.parse("2026-07-27T04:00:00Z");
    private static final String OWNER = "snapshot-runner-test";
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration INITIAL_DELAY = Duration.ofMinutes(1);
    private static final Duration MAX_DELAY = Duration.ofMinutes(10);

    private final WeeklySnapshotJobStore jobStore = mock(WeeklySnapshotJobStore.class);
    private final WeeklySnapshotJobExecutionService executionService = mock(
            WeeklySnapshotJobExecutionService.class
    );
    private final WeeklySnapshotOperatorSignals operatorSignals = mock(
            WeeklySnapshotOperatorSignals.class
    );
    private final WeeklySnapshotJobRunner runner = new WeeklySnapshotJobRunner(
            jobStore,
            executionService,
            new WeeklySnapshotJobFailureClassifier(),
            operatorSignals,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void returnsEmptyWhenQueueHasNoClaimableJob() {
        when(jobStore.claimNext(OWNER, LEASE, NOW)).thenReturn(Optional.empty());

        assertThat(runner.runNext(OWNER, LEASE, INITIAL_DELAY, MAX_DELAY)).isEmpty();

        verify(executionService, never()).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void retriesTransientDatabaseFailureWithCappedExponentialDelay() {
        WeeklySnapshotJob claimed = job(3);
        WeeklySnapshotJob waiting = mock(WeeklySnapshotJob.class);
        when(jobStore.claimNext(OWNER, LEASE, NOW)).thenReturn(Optional.of(claimed));
        when(executionService.execute(claimed, OWNER)).thenThrow(
                new TransientDataAccessResourceException("sensitive database detail")
        );
        when(jobStore.retryOrFail(
                claimed.id(),
                OWNER,
                true,
                "TRANSIENT_DATABASE",
                "Weekly snapshot execution failed: TRANSIENT_DATABASE",
                NOW.plus(Duration.ofMinutes(4)),
                NOW
        )).thenReturn(waiting);

        assertThat(runner.runNext(OWNER, LEASE, INITIAL_DELAY, MAX_DELAY))
                .contains(waiting);
    }

    @Test
    void failsContractViolationWithoutRetryOrLeakingExceptionMessage() {
        WeeklySnapshotJob claimed = job(1);
        WeeklySnapshotJob failed = mock(WeeklySnapshotJob.class);
        when(jobStore.claimNext(OWNER, LEASE, NOW)).thenReturn(Optional.of(claimed));
        when(executionService.execute(claimed, OWNER)).thenThrow(
                new IllegalArgumentException("customer-specific sensitive fact")
        );
        when(jobStore.retryOrFail(
                claimed.id(),
                OWNER,
                false,
                "SNAPSHOT_CONTRACT",
                "Weekly snapshot execution failed: SNAPSHOT_CONTRACT",
                NOW.plus(INITIAL_DELAY),
                NOW
        )).thenReturn(failed);
        when(failed.status()).thenReturn(WeeklySnapshotJobStatus.FAILED);

        assertThat(runner.runNext(OWNER, LEASE, INITIAL_DELAY, MAX_DELAY))
                .contains(failed);
        verify(operatorSignals).terminalFailure(failed);
    }

    private WeeklySnapshotJob job(int attemptCount) {
        return new WeeklySnapshotJob(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                WeeklySnapshotJobType.INITIAL,
                new StoreKpiPeriod(
                        LocalDate.of(2026, 7, 20),
                        LocalDate.of(2026, 7, 26)
                ),
                "Europe/Moscow",
                UUID.randomUUID(),
                NOW,
                new Versions(1, "metrics-v1", "calculation-v1", "quality-v1"),
                null,
                WeeklySnapshotJobStatus.RUNNING,
                null,
                null,
                attemptCount,
                5,
                NOW,
                OWNER,
                NOW.plus(LEASE),
                false,
                null,
                null,
                NOW,
                null,
                1,
                NOW,
                NOW
        );
    }
}
