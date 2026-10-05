package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SellerWeeklyPreparationStateMetricsTest {
    @Test
    void noFalseHealthyZerosAndOnlyFixedStatusLabelsAcrossFailureAndRecovery() {
        Instant now = Instant.parse("2026-10-06T00:00:00Z");
        var state = mock(SellerWeeklyPreparationOperationalState.class);
        var registry = new SimpleMeterRegistry();
        var metrics = new SellerWeeklyPreparationStateMetrics(state, Clock.fixed(now, ZoneOffset.UTC));
        metrics.bindTo(registry);
        assertThat(gauge(registry, "pending")).isNaN();
        assertThat(healthy(registry)).isZero();
        when(state.read(now)).thenReturn(new SellerWeeklyPreparationOperationalState.Counts(1, 2, 3, 4, 5, 6, 7, 8))
                .thenThrow(new IllegalStateException("must not print this"))
                .thenReturn(new SellerWeeklyPreparationOperationalState.Counts(0, 0, 0, 0, 0, 0, 0, 0));
        metrics.refresh();
        assertThat(gauge(registry, "waiting_sources")).isEqualTo(3);
        assertThat(gauge(registry, "waiting_history")).isEqualTo(4);
        assertThat(gauge(registry, "failed")).isEqualTo(5);
        assertThat(gauge(registry, "delayed")).isEqualTo(8);
        assertThat(healthy(registry)).isOne();
        assertThat(registry.get(SellerWeeklyPreparationStateMetrics.METRIC).gauges()).hasSize(8)
                .allSatisfy(gauge -> assertThat(gauge.getId().getTags()).hasSize(1));
        metrics.refresh();
        assertThat(gauge(registry, "failed")).isNaN();
        assertThat(healthy(registry)).isZero();
        metrics.refresh();
        assertThat(gauge(registry, "failed")).isZero();
        assertThat(healthy(registry)).isOne();
    }

    private double gauge(SimpleMeterRegistry registry, String status) {
        return registry.get(SellerWeeklyPreparationStateMetrics.METRIC).tag("status", status).gauge().value();
    }

    private double healthy(SimpleMeterRegistry registry) {
        return registry.get("storeanalytics.interpretation.seller.weekly.preparation.metrics.healthy").gauge().value();
    }
}
