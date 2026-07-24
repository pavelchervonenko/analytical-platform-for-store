package com.storeanalytics.sync.service;

import com.storeanalytics.sync.model.SyncScope;
import com.storeanalytics.sync.model.SyncTriggerType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class SyncMetrics {

    static final String DURATION_METRIC = "storeanalytics.sync.duration";

    private final MeterRegistry meterRegistry;

    public SyncMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public <T> T record(
            SyncScope scope,
            SyncTriggerType triggerType,
            Supplier<T> action
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return action.get();
        } catch (RuntimeException exception) {
            outcome = "failure";
            throw exception;
        } finally {
            sample.stop(Timer.builder(DURATION_METRIC)
                    .description("Duration of a synchronization operation")
                    .tag("scope", scope.name().toLowerCase(Locale.ROOT))
                    .tag("trigger", triggerType.name().toLowerCase(Locale.ROOT))
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meterRegistry));
        }
    }
}
