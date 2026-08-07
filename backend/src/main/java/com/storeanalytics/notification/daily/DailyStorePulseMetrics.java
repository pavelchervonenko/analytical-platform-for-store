package com.storeanalytics.notification.daily;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class DailyStorePulseMetrics {

    private static final String PLANS = "storeanalytics.notification.daily.pulse.plans";
    private static final String LAST_CREATED =
            "storeanalytics.notification.daily.pulse.last.created.timestamp";

    private final Counter created;
    private final Counter existing;
    private final Counter failed;
    private final AtomicLong lastCreatedEpochSeconds = new AtomicLong(Long.MIN_VALUE);

    public DailyStorePulseMetrics(MeterRegistry registry) {
        created = counter(registry, "created");
        existing = counter(registry, "existing");
        failed = counter(registry, "failed");
        Gauge.builder(LAST_CREATED, lastCreatedEpochSeconds, value -> {
            long epochSeconds = value.get();
            return epochSeconds == Long.MIN_VALUE ? Double.NaN : epochSeconds;
        }).description("Unix timestamp of the last newly persisted daily store pulse")
                .register(registry);
    }

    public void created(Instant at) {
        created.increment();
        lastCreatedEpochSeconds.set(at.getEpochSecond());
    }

    public void existing() {
        existing.increment();
    }

    public void failed() {
        failed.increment();
    }

    private Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder(PLANS)
                .description("Daily store pulse planning outcomes")
                .tag("outcome", outcome)
                .register(registry);
    }
}
