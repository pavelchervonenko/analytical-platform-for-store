package com.storeanalytics.interpretation.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WeeklySnapshotJobStateMetricsTest {

    @Test
    void exposesCachedOperationalJobCounts() {
        Instant now = Instant.parse("2026-07-27T04:00:00Z");
        WeeklySnapshotJobLifecycleStore store = mock(
                WeeklySnapshotJobLifecycleStore.class
        );
        when(store.countByStatus(WeeklySnapshotJobStatus.PENDING)).thenReturn(4L);
        when(store.countByStatus(WeeklySnapshotJobStatus.RUNNING)).thenReturn(1L);
        when(store.countByStatus(WeeklySnapshotJobStatus.FAILED)).thenReturn(2L);
        when(store.countByStatus(WeeklySnapshotJobStatus.WAITING_RETRY)).thenReturn(3L);
        when(store.countExpiredLeases(now)).thenReturn(1L);
        WeeklySnapshotJobStateMetrics metrics = new WeeklySnapshotJobStateMetrics(
                store,
                Clock.fixed(now, ZoneOffset.UTC)
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        metrics.refresh();

        assertGauge(registry, "pending", 4);
        assertGauge(registry, "running", 1);
        assertGauge(registry, "failed", 2);
        assertGauge(registry, "retrying", 3);
        assertGauge(registry, "expired_lease", 1);
    }

    private void assertGauge(
            SimpleMeterRegistry registry,
            String status,
            double expected
    ) {
        assertThat(registry.get(WeeklySnapshotJobStateMetrics.JOBS_METRIC)
                .tag("status", status)
                .gauge()
                .value()).isEqualTo(expected);
    }
}
