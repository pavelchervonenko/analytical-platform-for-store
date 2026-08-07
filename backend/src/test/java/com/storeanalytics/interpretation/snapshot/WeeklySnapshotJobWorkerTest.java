package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.storeanalytics.interpretation.config.WeeklySnapshotWorkerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WeeklySnapshotJobWorkerTest {

    private static final Duration LEASE = Duration.ofMinutes(10);
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(30);
    private static final Duration MAX_DELAY = Duration.ofMinutes(15);

    private final WeeklySnapshotJobCoordinator coordinator = mock(
            WeeklySnapshotJobCoordinator.class
    );
    private final WeeklySnapshotJobWorker worker = new WeeklySnapshotJobWorker(
            coordinator,
            new WeeklySnapshotWorkerProperties(
                    true,
                    Duration.ofSeconds(5),
                    LEASE,
                    Duration.ofMinutes(1),
                    INITIAL_DELAY,
                    MAX_DELAY
            )
    );

    @Test
    void delegatesOneIterationWithConfiguredPolicies() {
        worker.processNext();

        verify(coordinator).runNext(
                anyString(),
                org.mockito.ArgumentMatchers.eq(LEASE),
                org.mockito.ArgumentMatchers.eq(INITIAL_DELAY),
                org.mockito.ArgumentMatchers.eq(MAX_DELAY)
        );
    }

    @Test
    void heartbeatUsesSameWorkerIdentityAndDoesNotKillSchedulerOnFailure() {
        worker.heartbeat();
        verify(coordinator).heartbeatOwned(anyString(),
                org.mockito.ArgumentMatchers.eq(LEASE));

        doThrow(new IllegalStateException("database unavailable"))
                .when(coordinator).heartbeatOwned(anyString(),
                        org.mockito.ArgumentMatchers.eq(LEASE));
        assertThatCode(worker::heartbeat).doesNotThrowAnyException();
    }
}
