package com.storeanalytics.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TelegramDeliveryStateMetricsTest {

    @Test
    void exposesOnlyCachedDurableOperationalCounts() {
        Instant now = Instant.parse("2026-08-03T12:00:00Z");
        TelegramDeliveryOperationalStateStore store = mock(
                TelegramDeliveryOperationalStateStore.class
        );
        when(store.load(now)).thenReturn(new TelegramDeliveryOperationalState(
                1, 2, 3, 4, 5, 6, 7, 8
        ));
        TelegramDeliveryStateMetrics metrics = new TelegramDeliveryStateMetrics(
                store,
                Clock.fixed(now, ZoneOffset.UTC)
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        assertThat(gauge(registry, "ready_pending")).isNaN();

        metrics.refresh();

        assertGauge(registry, "ready_pending", 1);
        assertGauge(registry, "ready_retry", 2);
        assertGauge(registry, "authentication_retry", 3);
        assertGauge(registry, "running", 4);
        assertGauge(registry, "expired_lease", 5);
        assertGauge(registry, "permanent_failed", 6);
        assertGauge(registry, "unknown_outcome", 7);
        assertGauge(registry, "blocked_subscription", 8);
    }

    private void assertGauge(
            SimpleMeterRegistry registry,
            String status,
            double expected
    ) {
        assertThat(gauge(registry, status)).isEqualTo(expected);
    }

    private double gauge(SimpleMeterRegistry registry, String status) {
        return registry.get(TelegramDeliveryStateMetrics.STATE_METRIC)
                .tag("channel", "TELEGRAM")
                .tag("status", status)
                .gauge()
                .value();
    }
}
