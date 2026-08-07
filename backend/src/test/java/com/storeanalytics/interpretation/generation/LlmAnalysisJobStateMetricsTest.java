package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LlmAnalysisJobStateMetricsTest {

    @Test
    void exposesCachedOperationalState() {
        Instant now = Instant.parse("2026-08-03T05:00:00Z");
        LlmAnalysisJobLifecycleStore store = mock(LlmAnalysisJobLifecycleStore.class);
        when(store.countByStatus(LlmAnalysisJobStatus.PENDING)).thenReturn(2L);
        when(store.countByStatus(LlmAnalysisJobStatus.RUNNING)).thenReturn(1L);
        when(store.countByStatus(LlmAnalysisJobStatus.WAITING_RETRY)).thenReturn(3L);
        when(store.countByStatus(LlmAnalysisJobStatus.FAILED)).thenReturn(4L);
        when(store.countByStatus(LlmAnalysisJobStatus.VALIDATION_FAILED)).thenReturn(5L);
        when(store.countByStatus(LlmAnalysisJobStatus.SKIPPED)).thenReturn(6L);
        when(store.countDeadlineExceeded()).thenReturn(7L);
        when(store.countExpiredLeases(now)).thenReturn(1L);
        LlmAnalysisJobStateMetrics metrics = new LlmAnalysisJobStateMetrics(
                store,
                Clock.fixed(now, ZoneOffset.UTC)
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        metrics.refresh();

        assertGauge(registry, "pending", 2);
        assertGauge(registry, "running", 1);
        assertGauge(registry, "retrying", 3);
        assertGauge(registry, "failed", 4);
        assertGauge(registry, "validation_failed", 5);
        assertGauge(registry, "skipped", 6);
        assertGauge(registry, "deadline_exceeded", 7);
        assertGauge(registry, "expired_lease", 1);
    }

    private void assertGauge(
            SimpleMeterRegistry registry,
            String status,
            double expected
    ) {
        assertThat(registry.get(LlmAnalysisJobStateMetrics.JOBS_METRIC)
                .tag("status", status)
                .gauge()
                .value()).isEqualTo(expected);
    }
}
