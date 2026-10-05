package com.storeanalytics.interpretation.review;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.BackgroundSchedulingConfiguration;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** No store/week/employee labels, network requests or queue writes. */
@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(prefix = "app.interpretation.seller-weekly-preparation", name = "enabled", havingValue = "true")
public class SellerWeeklyPreparationStateMetrics implements MeterBinder {
    static final String METRIC = "storeanalytics.interpretation.seller.weekly.preparation.jobs";
    private static final Logger LOGGER = LoggerFactory.getLogger(SellerWeeklyPreparationStateMetrics.class);
    private final SellerWeeklyPreparationOperationalState state;
    private final Clock clock;
    private volatile SellerWeeklyPreparationOperationalState.Counts counts;

    public SellerWeeklyPreparationStateMetrics(SellerWeeklyPreparationOperationalState state, Clock clock) {
        this.state = state;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "pending", SellerWeeklyPreparationOperationalState.Counts::pending);
        gauge(registry, "running", SellerWeeklyPreparationOperationalState.Counts::running);
        gauge(registry, "waiting_sources", SellerWeeklyPreparationOperationalState.Counts::waitingSources);
        gauge(registry, "waiting_history", SellerWeeklyPreparationOperationalState.Counts::waitingHistory);
        gauge(registry, "failed", SellerWeeklyPreparationOperationalState.Counts::failed);
        gauge(registry, "succeeded", SellerWeeklyPreparationOperationalState.Counts::succeeded);
        gauge(registry, "expired_lease", SellerWeeklyPreparationOperationalState.Counts::expiredLease);
        gauge(registry, "delayed", SellerWeeklyPreparationOperationalState.Counts::delayed);
        Gauge.builder("storeanalytics.interpretation.seller.weekly.preparation.metrics.healthy", this,
                metrics -> metrics.counts == null ? 0 : 1).register(registry);
    }

    @Scheduled(initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}",
            scheduler = BackgroundSchedulingConfiguration.METRICS_SCHEDULER)
    public void refresh() {
        try {
            counts = state.read(clock.instant());
        } catch (RuntimeException failure) {
            counts = null;
            LOGGER.error("Seller weekly preparation metrics unavailable; failure_type={}",
                    failure.getClass().getSimpleName());
        }
    }

    private void gauge(MeterRegistry registry, String status,
            ToLongFunction<SellerWeeklyPreparationOperationalState.Counts> value) {
        Gauge.builder(METRIC, this, metrics -> {
            var observed = metrics.counts;
            return observed == null ? Double.NaN : value.applyAsLong(observed);
        }).tag("status", status).register(registry);
    }
}
