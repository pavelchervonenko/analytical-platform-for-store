package com.storeanalytics.integration.livesklad.observability;

import com.storeanalytics.integration.livesklad.exception.LiveSkladPayloadRejectedException.Reason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LiveSkladPayloadRejectionMetrics {

    public static final String REJECTIONS_METRIC =
            "storeanalytics.livesklad.payload.rejections";

    private final Map<Reason, Counter> counters;

    public LiveSkladPayloadRejectionMetrics(MeterRegistry meterRegistry) {
        EnumMap<Reason, Counter> registeredCounters = new EnumMap<>(Reason.class);
        for (Reason reason : Reason.values()) {
            registeredCounters.put(
                    reason,
                    Counter.builder(REJECTIONS_METRIC)
                            .description("Rejected LiveSklad payloads")
                            .tag("reason", reason.name().toLowerCase(Locale.ROOT))
                            .register(meterRegistry)
            );
        }
        counters = Map.copyOf(registeredCounters);
    }

    private LiveSkladPayloadRejectionMetrics() {
        counters = Map.of();
    }

    public static LiveSkladPayloadRejectionMetrics noop() {
        return new LiveSkladPayloadRejectionMetrics();
    }

    public void record(Reason reason) {
        Counter counter = counters.get(reason);
        if (counter != null) {
            counter.increment();
        }
    }
}
