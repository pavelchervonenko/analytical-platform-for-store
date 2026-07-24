package com.storeanalytics.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.store.repository.DataFreshnessRepository;
import com.storeanalytics.store.repository.DataFreshnessSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DataFreshnessMetricsTest {

    private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

    @Test
    void exposesWorstAgeAndMissingStoreCounts() {
        DataFreshnessRepository repository = mock(DataFreshnessRepository.class);
        when(repository.load()).thenReturn(new DataFreshnessSnapshot(
                NOW.minusSeconds(3600),
                NOW.minusSeconds(7200),
                1,
                2
        ));
        DataFreshnessMetrics metrics = new DataFreshnessMetrics(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        metrics.refresh();

        assertThat(registry.get(DataFreshnessMetrics.AGE_METRIC)
                .tag("source", "sales")
                .gauge()
                .value()).isEqualTo(3600);
        assertThat(registry.get(DataFreshnessMetrics.AGE_METRIC)
                .tag("source", "returns")
                .gauge()
                .value()).isEqualTo(7200);
        assertThat(registry.get(DataFreshnessMetrics.MISSING_METRIC)
                .tag("source", "sales")
                .gauge()
                .value()).isEqualTo(1);
    }
}
