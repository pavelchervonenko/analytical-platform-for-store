package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.common.config.SyncProperties;
import com.storeanalytics.integration.connection.model.IntegrationConnection;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.sync.model.SourceSystem;
import com.storeanalytics.sync.repository.HistoricalSalesRefreshStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistoricalSalesRefreshMetricsTest {
    private static final Instant NOW = Instant.parse("2026-10-06T06:00:00Z");
    private static final Instant START = Instant.parse("2026-09-30T22:00:00Z");
    private final HistoricalSalesRefreshStore states = mock(HistoricalSalesRefreshStore.class);
    private final IntegrationConnectionRepository connections = mock(IntegrationConnectionRepository.class);
    private final IntegrationConnection connection = new IntegrationConnection("livesklad-default",
            SourceSystem.LIVESKLAD, "Fixture", null, null);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HistoricalSalesRefreshMetrics metrics = new HistoricalSalesRefreshMetrics(states, connections,
            Clock.fixed(NOW, ZoneOffset.UTC), new SyncProperties(Duration.ofHours(3), 5, Duration.ofHours(2),
                    Duration.ofMinutes(1), Duration.ofMinutes(15), Duration.ofDays(1), 3, 730,
                    ZoneId.of("Europe/Kaliningrad")));

    @Test
    void firstCycleStallAndDurableFailureRemainVisibleWithoutRetainedJob() {
        bind(new HistoricalSalesRefreshStore.State(connection.getId(), START, START, START.plusSeconds(10800),
                null, 180, null, null, null, LocalDate.of(2026, 10, 6), 23, NOW.minusSeconds(3600),
                NOW.minusSeconds(600), START, START.plusSeconds(10800), "PERMANENT_FAILURE",
                START, START.plusSeconds(10800), true, false, List.of()));
        assertThat(value("blocked")).isEqualTo(1);
        assertThat(value("pending_span_seconds")).isEqualTo(10800);
        assertThat(value("pending_cycle_age_seconds")).isEqualTo(3600);
        assertThat(value("last_success_age_seconds")).isEqualTo(3600);
        assertThat(value("request_attempts_today")).isEqualTo(23);
        assertThat(value("completed_cycle_age_seconds")).isNaN();
    }

    @Test
    void classificationRequiredIsBlockedButCompletedCycleHasNoPendingAge() {
        bind(new HistoricalSalesRefreshStore.State(connection.getId(), START, START, START.plusSeconds(10800),
                null, 180, null, null, null, LocalDate.of(2026, 10, 5), 200, NOW.minusSeconds(1200),
                null, null, null, "CLASSIFICATION_REQUIRED", null, null, true, false, List.of()));
        assertThat(value("blocked")).isEqualTo(1);
        assertThat(value("request_attempts_today")).isZero();
        bind(new HistoricalSalesRefreshStore.State(connection.getId(), START, START.plusSeconds(10800),
                START.plusSeconds(10800), null, 180, NOW.minusSeconds(600), NOW.minusSeconds(600), NOW.plusSeconds(600),
                LocalDate.of(2026, 10, 6), 10, NOW.minusSeconds(1200), null, null, null, null, null, null,
                true, false, List.of()));
        assertThat(value("blocked")).isZero();
        assertThat(value("pending_cycle_age_seconds")).isZero();
        assertThat(value("last_success_age_seconds")).isEqualTo(600);
    }

    @Test
    void enabledButNotInitializedCannotLookLikeHealthyIdle() {
        when(connections.findByConnectionKeyAndActiveTrue("livesklad-default")).thenReturn(Optional.empty());
        metrics.bindTo(registry);
        metrics.refresh();
        assertThat(value("blocked")).isEqualTo(1);
        assertThat(value("pending_cycle_age_seconds")).isFinite();
    }

    @Test
    void failedObservationIsUnknownRatherThanHealthy() {
        when(connections.findByConnectionKeyAndActiveTrue("livesklad-default"))
                .thenThrow(new IllegalStateException("fixture unavailable"));
        metrics.bindTo(registry);
        metrics.refresh();
        assertThat(value("blocked")).isNaN();
        assertThat(value("last_success_age_seconds")).isNaN();
    }

    private void bind(HistoricalSalesRefreshStore.State state) {
        when(connections.findByConnectionKeyAndActiveTrue("livesklad-default")).thenReturn(Optional.of(connection));
        when(states.find(connection.getId(), false)).thenReturn(Optional.of(state));
        metrics.bindTo(registry);
        metrics.refresh();
    }

    private double value(String metric) {
        return registry.get("storeanalytics.sync.history." + metric).gauge().value();
    }
}
