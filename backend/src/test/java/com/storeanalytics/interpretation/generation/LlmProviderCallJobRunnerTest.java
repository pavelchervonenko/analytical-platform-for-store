package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LlmProviderCallJobRunnerTest {

    @Test
    void heartbeatsOnlyWhileProviderCallIsActive() throws Exception {
        LlmProviderCallCoordinator coordinator = mock(LlmProviderCallCoordinator.class);
        LlmProviderCallExecutionService execution = mock(
                LlmProviderCallExecutionService.class
        );
        LlmAnalysisJob claimed = mock(LlmAnalysisJob.class);
        LlmAnalysisJob completed = mock(LlmAnalysisJob.class);
        UUID jobId = UUID.randomUUID();
        Duration lease = Duration.ofMinutes(2);
        Duration recovery = Duration.ofSeconds(30);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(claimed.id()).thenReturn(jobId);
        when(coordinator.claimNext("worker", lease, recovery))
                .thenReturn(Optional.of(claimed));
        when(execution.execute(claimed, "worker")).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return completed;
        });
        when(coordinator.heartbeat(jobId, "worker", lease)).thenReturn(claimed);
        LlmProviderCallJobRunner runner = new LlmProviderCallJobRunner(
                coordinator,
                execution
        );

        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> runner.runNext(
                    "worker", lease, recovery
            ));
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.heartbeatCurrent(lease)).contains(claimed);
            release.countDown();
            assertThat(result.get(10, TimeUnit.SECONDS)).contains(completed);
        }

        assertThat(runner.heartbeatCurrent(lease)).isEmpty();
        verify(coordinator).heartbeat(jobId, "worker", lease);
    }
}
