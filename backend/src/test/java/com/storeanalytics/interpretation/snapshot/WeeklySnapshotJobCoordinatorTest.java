package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WeeklySnapshotJobCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-27T04:00:00Z");

    private final WeeklySnapshotJobLifecycleStore lifecycleStore = mock(
            WeeklySnapshotJobLifecycleStore.class
    );
    private final WeeklySnapshotJobControlStore controlStore = mock(
            WeeklySnapshotJobControlStore.class
    );
    private final WeeklySnapshotJobRunner runner = mock(WeeklySnapshotJobRunner.class);
    private final WeeklySnapshotOperatorSignals operatorSignals = mock(
            WeeklySnapshotOperatorSignals.class
    );
    private final WeeklySnapshotJobCoordinator coordinator =
            new WeeklySnapshotJobCoordinator(
                    lifecycleStore,
                    controlStore,
                    runner,
                    operatorSignals,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );

    @Test
    void recoversOneExpiredLeaseBeforeDelegatingClaimAndExecution() {
        Duration lease = Duration.ofMinutes(5);
        Duration initialDelay = Duration.ofMinutes(1);
        Duration maxDelay = Duration.ofMinutes(10);
        WeeklySnapshotJob completed = mock(WeeklySnapshotJob.class);
        WeeklySnapshotJob recovered = mock(WeeklySnapshotJob.class);
        when(lifecycleStore.recoverOneExpiredLease(
                NOW.plus(initialDelay), NOW)).thenReturn(Optional.of(recovered));
        when(runner.runNext("worker", lease, initialDelay, maxDelay))
                .thenReturn(Optional.of(completed));

        assertThat(coordinator.runNext("worker", lease, initialDelay, maxDelay))
                .contains(completed);

        InOrder order = inOrder(lifecycleStore, runner);
        order.verify(lifecycleStore).recoverOneExpiredLease(
                NOW.plus(initialDelay),
                NOW
        );
        verify(operatorSignals).expiredLeaseRecovered(recovered);
        order.verify(runner).runNext("worker", lease, initialDelay, maxDelay);
    }

    @Test
    void delegatesHeartbeatAndCancellationUsingUtcClock() {
        UUID jobId = UUID.randomUUID();
        Duration lease = Duration.ofMinutes(5);
        WeeklySnapshotJob heartbeat = mock(WeeklySnapshotJob.class);
        WeeklySnapshotJob cancelled = mock(WeeklySnapshotJob.class);
        when(lifecycleStore.heartbeat(jobId, "worker", lease, NOW))
                .thenReturn(heartbeat);
        when(lifecycleStore.requestCancellation(jobId, NOW)).thenReturn(cancelled);

        assertThat(coordinator.heartbeat(jobId, "worker", lease)).isSameAs(heartbeat);
        assertThat(coordinator.cancel(jobId)).isSameAs(cancelled);
    }
}
