package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class WeeklySnapshotOperatorSignalsTest {

    @Test
    void countsTerminalFailuresAndRecoveredExpiredLeases() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WeeklySnapshotOperatorSignals signals = new WeeklySnapshotOperatorSignals(registry);
        WeeklySnapshotJob failed = job(WeeklySnapshotJobStatus.FAILED);
        WeeklySnapshotJob retrying = job(WeeklySnapshotJobStatus.WAITING_RETRY);

        signals.terminalFailure(failed);
        signals.expiredLeaseRecovered(retrying);
        signals.expiredLeaseRecovered(failed);

        assertCounter(registry, "terminal_failure", 2);
        assertCounter(registry, "expired_lease_recovered", 2);
    }

    private WeeklySnapshotJob job(WeeklySnapshotJobStatus status) {
        WeeklySnapshotJob job = mock(WeeklySnapshotJob.class);
        when(job.status()).thenReturn(status);
        when(job.attemptCount()).thenReturn(2);
        when(job.maxAttempts()).thenReturn(3);
        return job;
    }

    private void assertCounter(
            SimpleMeterRegistry registry,
            String event,
            double expected
    ) {
        assertThat(registry.get(WeeklySnapshotOperatorSignals.EVENTS_METRIC)
                .tag("event", event)
                .counter()
                .count()).isEqualTo(expected);
    }
}
