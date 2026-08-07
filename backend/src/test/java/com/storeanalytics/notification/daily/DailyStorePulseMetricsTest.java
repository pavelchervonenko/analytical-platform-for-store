package com.storeanalytics.notification.daily;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DailyStorePulseMetricsTest {

    @Test
    void registersBoundedOutcomesAndLastCreatedTimestamp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DailyStorePulseMetrics metrics = new DailyStorePulseMetrics(registry);

        assertThat(registry.get("storeanalytics.notification.daily.pulse.plans")
                .tag("outcome", "created").counter().count()).isZero();
        assertThat(registry.get(
                "storeanalytics.notification.daily.pulse.last.created.timestamp"
        ).gauge().value()).isNaN();

        metrics.created(Instant.parse("2026-08-03T05:05:00Z"));
        metrics.existing();
        metrics.failed();

        assertThat(registry.get("storeanalytics.notification.daily.pulse.plans")
                .tag("outcome", "created").counter().count()).isEqualTo(1);
        assertThat(registry.get("storeanalytics.notification.daily.pulse.plans")
                .tag("outcome", "existing").counter().count()).isEqualTo(1);
        assertThat(registry.get("storeanalytics.notification.daily.pulse.plans")
                .tag("outcome", "failed").counter().count()).isEqualTo(1);
        assertThat(registry.get(
                "storeanalytics.notification.daily.pulse.last.created.timestamp"
        ).gauge().value()).isEqualTo(1785733500d);
    }
}
