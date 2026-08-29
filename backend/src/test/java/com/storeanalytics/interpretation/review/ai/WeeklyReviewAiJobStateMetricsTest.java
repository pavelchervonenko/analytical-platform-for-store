package com.storeanalytics.interpretation.review.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WeeklyReviewAiJobStateMetricsTest {

    @Test
    void exposesCachedLifecycleAndStalenessCounts() {
        Instant now = Instant.parse("2026-08-27T12:00:00Z");
        WeeklyReviewAiJobStore store = mock(WeeklyReviewAiJobStore.class);
        when(store.countByStatus(WeeklyReviewAiJobStatus.PENDING)).thenReturn(2L);
        when(store.countByStatus(WeeklyReviewAiJobStatus.RUNNING)).thenReturn(1L);
        when(store.countByStatus(WeeklyReviewAiJobStatus.RETRY_WAIT)).thenReturn(3L);
        when(store.countByStatus(WeeklyReviewAiJobStatus.SUCCEEDED)).thenReturn(4L);
        when(store.countByStatus(WeeklyReviewAiJobStatus.FAILED)).thenReturn(5L);
        when(store.countDelayed(now.minusSeconds(300))).thenReturn(6L);
        when(store.countExpiredLeases(now)).thenReturn(7L);
        WeeklyReviewAiJobStateMetrics metrics = new WeeklyReviewAiJobStateMetrics(
                store,
                WeeklyReviewAiTestProperties.properties(true, true, true),
                Clock.fixed(now, ZoneOffset.UTC)
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        metrics.refresh();

        assertGauge(registry, "pending", 2);
        assertGauge(registry, "running", 1);
        assertGauge(registry, "retry_wait", 3);
        assertGauge(registry, "succeeded", 4);
        assertGauge(registry, "failed", 5);
        assertGauge(registry, "delayed", 6);
        assertGauge(registry, "expired_lease", 7);
    }

    private void assertGauge(
            SimpleMeterRegistry registry,
            String status,
            double expected
    ) {
        assertThat(registry.get(WeeklyReviewAiJobStateMetrics.JOBS_METRIC)
                .tag("status", status)
                .gauge()
                .value()).isEqualTo(expected);
    }
}
