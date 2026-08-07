package com.storeanalytics.notification.daily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DailyStorePulseStateMetricsTest {

    @Test
    void restoresLatestDurableEventTimestampAfterProcessRestart() {
        DailyStorePulseOperationalStateStore store = mock(
                DailyStorePulseOperationalStateStore.class
        );
        when(store.lastCreatedAt()).thenReturn(
                Instant.parse("2026-08-03T05:05:00Z")
        );
        DailyStorePulseStateMetrics metrics = new DailyStorePulseStateMetrics(store);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        assertThat(registry.get(DailyStorePulseStateMetrics.LAST_EVENT)
                .gauge().value()).isNaN();

        metrics.refresh();

        assertThat(registry.get(DailyStorePulseStateMetrics.LAST_EVENT)
                .gauge().value()).isEqualTo(1785733500d);
    }
}
