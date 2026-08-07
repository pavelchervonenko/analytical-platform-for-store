package com.storeanalytics.notification.delivery;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class TelegramDeliveryStateMetrics implements MeterBinder {

    static final String STATE_METRIC = "storeanalytics.notification.delivery.state";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            TelegramDeliveryStateMetrics.class
    );

    private final TelegramDeliveryOperationalStateStore stateStore;
    private final Clock clock;
    private final AtomicReference<MetricState> state = new AtomicReference<>(
            MetricState.unknown()
    );

    public TelegramDeliveryStateMetrics(
            TelegramDeliveryOperationalStateStore stateStore,
            Clock clock
    ) {
        this.stateStore = stateStore;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "ready_pending", MetricState::readyPending);
        gauge(registry, "ready_retry", MetricState::readyRetry);
        gauge(registry, "authentication_retry", MetricState::authenticationRetry);
        gauge(registry, "running", MetricState::running);
        gauge(registry, "expired_lease", MetricState::expiredLease);
        gauge(registry, "permanent_failed", MetricState::permanentFailed);
        gauge(registry, "unknown_outcome", MetricState::unknownOutcome);
        gauge(registry, "blocked_subscription", MetricState::blockedSubscription);
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER
    )
    public void refresh() {
        try {
            TelegramDeliveryOperationalState loaded = stateStore.load(clock.instant());
            state.set(new MetricState(
                    loaded.readyPending(),
                    loaded.readyRetry(),
                    loaded.authenticationRetry(),
                    loaded.running(),
                    loaded.expiredLease(),
                    loaded.permanentFailed(),
                    loaded.unknownOutcome(),
                    loaded.blockedSubscription()
            ));
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh Telegram delivery state metrics", exception);
        }
    }

    private void gauge(
            MeterRegistry registry,
            String status,
            ToDoubleFunction<MetricState> value
    ) {
        Gauge.builder(STATE_METRIC, state, current -> value.applyAsDouble(current.get()))
                .description("Current durable Telegram delivery operational state")
                .tag("channel", "TELEGRAM")
                .tag("status", status)
                .register(registry);
    }

    private record MetricState(
            double readyPending,
            double readyRetry,
            double authenticationRetry,
            double running,
            double expiredLease,
            double permanentFailed,
            double unknownOutcome,
            double blockedSubscription
    ) {

        private static MetricState unknown() {
            return new MetricState(
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN
            );
        }
    }
}
