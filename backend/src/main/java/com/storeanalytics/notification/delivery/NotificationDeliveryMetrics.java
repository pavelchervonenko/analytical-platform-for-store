package com.storeanalytics.notification.delivery;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryMetrics {

    public static final String OUTCOMES = "storeanalytics.notification.delivery.total";
    public static final String LATENCY = "storeanalytics.notification.delivery.latency";

    private final MeterRegistry registry;

    public NotificationDeliveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void outcome(String outcome) {
        registry.counter(OUTCOMES, "channel", "TELEGRAM", "outcome", outcome)
                .increment();
    }

    public void latency(long latencyMs) {
        registry.timer(LATENCY, "channel", "TELEGRAM")
                .record(Duration.ofMillis(latencyMs));
    }
}
