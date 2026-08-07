package com.storeanalytics.notification.fanout;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class NotificationFanoutMetrics {

    private static final String METRIC = "storeanalytics.notification.fanout.total";
    private final Map<NotificationFanoutOutcome, Counter> counters = new EnumMap<>(
            NotificationFanoutOutcome.class
    );

    public NotificationFanoutMetrics(MeterRegistry registry) {
        for (NotificationFanoutOutcome outcome : NotificationFanoutOutcome.values()) {
            counters.put(outcome, Counter.builder(METRIC)
                    .description("Completed notification event fanout projections")
                    .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                    .register(registry));
        }
    }

    public void completed(NotificationFanoutOutcome outcome) {
        counters.get(outcome).increment();
    }
}
