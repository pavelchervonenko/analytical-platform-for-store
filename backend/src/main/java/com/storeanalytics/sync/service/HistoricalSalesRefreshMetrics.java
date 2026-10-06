package com.storeanalytics.sync.service;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.common.config.SyncProperties;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.sync.repository.HistoricalSalesRefreshStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Operational sweep lag, not provider mutation latency or global source completeness. */
@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(prefix = "app.sync.historical-sales", name = "enabled", havingValue = "true")
public class HistoricalSalesRefreshMetrics implements MeterBinder {
    private final HistoricalSalesRefreshStore states;
    private final IntegrationConnectionRepository connections;
    private final Clock clock;
    private final SyncProperties sync;
    private final Instant enabledAt;
    private final AtomicReference<Values> current = new AtomicReference<>(Values.unknown());

    public HistoricalSalesRefreshMetrics(HistoricalSalesRefreshStore states,
                                        IntegrationConnectionRepository connections,
                                        Clock clock, SyncProperties sync) {
        this.states = states;
        this.connections = connections;
        this.clock = clock;
        this.sync = sync;
        this.enabledAt = clock.instant();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("storeanalytics.sync.history.pending_span_seconds", current,
                ref -> ref.get().pendingSpan()).register(registry);
        Gauge.builder("storeanalytics.sync.history.completed_cycle_age_seconds", current,
                ref -> ref.get().cycleAge()).register(registry);
        Gauge.builder("storeanalytics.sync.history.pending_cycle_age_seconds", current,
                ref -> ref.get().pendingCycleAge()).register(registry);
        Gauge.builder("storeanalytics.sync.history.last_success_age_seconds", current,
                ref -> ref.get().lastSuccessAge()).register(registry);
        Gauge.builder("storeanalytics.sync.history.request_attempts_today", current,
                ref -> ref.get().attemptsToday()).register(registry);
        Gauge.builder("storeanalytics.sync.history.blocked", current,
                ref -> ref.get().blocked()).register(registry);
    }

    @Scheduled(fixedDelayString = "${app.observability.state-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER)
    public void refresh() {
        try {
            var connection = connections.findByConnectionKeyAndActiveTrue("livesklad-default");
            var state = connection.flatMap(value -> states.find(value.getId(), false));
            if (state.isEmpty()) {
                // Enabled but not initialized (e.g. routine sync has not succeeded) is not healthy idle.
                current.set(new Values(Double.NaN, Double.NaN, age(enabledAt), age(enabledAt), 0, 1));
                return;
            }
            var value = state.orElseThrow();
            double blocked = value.blockReason() == null ? 0 : 1;
            current.set(new Values(Duration.between(value.cursorStart(), value.cycleEnd()).toSeconds(),
                    value.lastCycleCompletedAt() == null ? Double.NaN
                            : age(value.lastCycleCompletedAt()),
                    value.cursorStart().isBefore(value.cycleEnd()) ? age(value.cycleStartedAt()) : 0,
                    age(value.lastSuccessAt() == null ? value.cycleStartedAt() : value.lastSuccessAt()),
                    value.budgetDay().equals(LocalDate.now(clock.withZone(sync.reportingZone())))
                            ? value.requestAttempts() : 0, blocked));
        } catch (RuntimeException exception) {
            current.set(Values.unknown());
        }
    }

    private double age(Instant since) {
        return Math.max(0, Duration.between(since, clock.instant()).toSeconds());
    }

    private record Values(double pendingSpan, double cycleAge, double pendingCycleAge,
                          double lastSuccessAge, double attemptsToday, double blocked) {
        private static Values unknown() {
            return new Values(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }
}
