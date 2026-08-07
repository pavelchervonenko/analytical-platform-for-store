package com.storeanalytics.notification.daily;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
public class DailyStorePulseStateMetrics implements MeterBinder {

    static final String LAST_EVENT =
            "storeanalytics.notification.daily.pulse.last.event.timestamp";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            DailyStorePulseStateMetrics.class
    );

    private final DailyStorePulseOperationalStateStore stateStore;
    private final AtomicReference<Double> lastCreated = new AtomicReference<>(Double.NaN);

    public DailyStorePulseStateMetrics(DailyStorePulseOperationalStateStore stateStore) {
        this.stateStore = stateStore;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(LAST_EVENT, lastCreated, AtomicReference::get)
                .description("Unix timestamp of the latest durable daily store pulse event")
                .register(registry);
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER
    )
    public void refresh() {
        try {
            Instant value = stateStore.lastCreatedAt();
            lastCreated.set(value == null ? Double.NaN : (double) value.getEpochSecond());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh daily store pulse state metrics", exception);
        }
    }
}
