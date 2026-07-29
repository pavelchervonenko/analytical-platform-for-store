package com.storeanalytics.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DataRetentionMetricsTest {

    @Test
    void recordsBoundedOutcomeAffectedRowsAndLastSuccess() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-24T10:00:00Z"),
                ZoneOffset.UTC
        );
        DataRetentionMetrics metrics = new DataRetentionMetrics(registry, clock);
        metrics.bindTo(registry);
        DataRetentionRunResult completed = new DataRetentionRunResult(
                UUID.randomUUID(),
                true,
                false,
                Map.of("raw_record_versions", 5L),
                Map.of("raw_record_versions", 3L),
                Map.of("raw_record_versions", 2L)
        );

        assertThat(metrics.record(() -> completed)).isSameAs(completed);

        assertThat(registry.get(DataRetentionMetrics.DURATION_METRIC)
                .tag("outcome", "success")
                .timer()
                .count()).isOne();
        assertThat(registry.get(DataRetentionMetrics.AFFECTED_METRIC)
                .tag("target", "raw_record_versions")
                .counter()
                .count()).isEqualTo(3);
        assertThat(registry.get(DataRetentionMetrics.LAST_SUCCESS_METRIC)
                .gauge()
                .value()).isEqualTo(clock.instant().getEpochSecond());

        DataRetentionRunResult skipped = DataRetentionRunResult.skipped(
                UUID.randomUUID(),
                true
        );
        assertThat(metrics.record(() -> skipped)).isSameAs(skipped);
        assertThat(registry.get(DataRetentionMetrics.DURATION_METRIC)
                .tag("outcome", "skipped")
                .timer()
                .count()).isOne();
    }
}
