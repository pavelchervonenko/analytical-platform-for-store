package com.storeanalytics.maintenance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class DataRetentionMetrics implements MeterBinder {

    static final String DURATION_METRIC = "storeanalytics.maintenance.retention.duration";
    static final String AFFECTED_METRIC = "storeanalytics.maintenance.retention.affected";
    static final String LAST_SUCCESS_METRIC =
            "storeanalytics.maintenance.retention.last.success.timestamp";

    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final AtomicReference<Double> lastSuccess = new AtomicReference<>(
            Double.NaN
    );

    public DataRetentionMetrics(MeterRegistry meterRegistry, Clock clock) {
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(LAST_SUCCESS_METRIC, lastSuccess, AtomicReference::get)
                .description("Unix timestamp of the last successful retention run")
                .register(registry);
    }

    public DataRetentionRunResult record(Supplier<DataRetentionRunResult> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            DataRetentionRunResult result = action.get();
            if (!result.lockAcquired()) {
                outcome = "skipped";
                return result;
            }
            result.affected().forEach(this::incrementAffected);
            lastSuccess.set((double) clock.instant().getEpochSecond());
            return result;
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            sample.stop(Timer.builder(DURATION_METRIC)
                    .description("Duration of technical data retention maintenance")
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }

    private void incrementAffected(String target, long amount) {
        if (amount == 0) {
            return;
        }
        Counter.builder(AFFECTED_METRIC)
                .description("Rows aggregated or deleted by retention maintenance")
                .tag("target", target)
                .register(meterRegistry)
                .increment(amount);
    }
}
