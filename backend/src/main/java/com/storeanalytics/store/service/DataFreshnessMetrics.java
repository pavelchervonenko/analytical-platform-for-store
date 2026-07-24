package com.storeanalytics.store.service;

import com.storeanalytics.store.repository.DataFreshnessRepository;
import com.storeanalytics.store.repository.DataFreshnessSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DataFreshnessMetrics implements MeterBinder {

    static final String AGE_METRIC = "storeanalytics.data.freshness.age";
    static final String MISSING_METRIC = "storeanalytics.data.freshness.missing";

    private static final Logger LOGGER = LoggerFactory.getLogger(
            DataFreshnessMetrics.class
    );

    private final DataFreshnessRepository repository;
    private final Clock clock;
    private final AtomicReference<DataFreshnessSnapshot> snapshot =
            new AtomicReference<>(new DataFreshnessSnapshot(
                    null, null, 0, 0
            ));

    public DataFreshnessMetrics(
            DataFreshnessRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        ageGauge(registry, "sales", DataFreshnessSnapshot::oldestSalesThrough);
        ageGauge(registry, "returns", DataFreshnessSnapshot::oldestReturnsThrough);
        missingGauge(
                registry, "sales",
                value -> value.storesWithoutSales()
        );
        missingGauge(
                registry, "returns",
                value -> value.storesWithoutReturns()
        );
    }

    @Scheduled(
            initialDelayString = "${app.observability.state-initial-delay:30s}",
            fixedDelayString = "${app.observability.state-refresh-delay:1m}"
    )
    public void refresh() {
        try {
            snapshot.set(repository.load());
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to refresh data freshness metrics", exception);
        }
    }

    private void ageGauge(
            MeterRegistry registry,
            String source,
            java.util.function.Function<DataFreshnessSnapshot, Instant> value
    ) {
        Gauge.builder(
                        AGE_METRIC,
                        snapshot,
                        state -> ageSeconds(value.apply(state.get()))
                )
                .description("Worst age of synchronized data among active stores")
                .baseUnit("seconds")
                .tag("source", source)
                .register(registry);
    }

    private void missingGauge(
            MeterRegistry registry,
            String source,
            ToDoubleFunction<DataFreshnessSnapshot> value
    ) {
        Gauge.builder(
                        MISSING_METRIC,
                        snapshot,
                        state -> value.applyAsDouble(state.get())
                )
                .description("Active stores without successful synchronized data")
                .tag("source", source)
                .register(registry);
    }

    private double ageSeconds(Instant dataThrough) {
        if (dataThrough == null) {
            return Double.NaN;
        }
        return Math.max(
                0,
                Duration.between(dataThrough, clock.instant()).toSeconds()
        );
    }
}
