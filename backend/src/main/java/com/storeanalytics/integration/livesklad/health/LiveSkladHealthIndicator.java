package com.storeanalytics.integration.livesklad.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class LiveSkladHealthIndicator implements HealthIndicator {

    private final LiveSkladAvailabilityProbe probe;

    public LiveSkladHealthIndicator(LiveSkladAvailabilityProbe probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        LiveSkladAvailabilityState state = probe.current();
        Health.Builder builder = switch (state.availability()) {
            case UP -> Health.up();
            case DOWN -> Health.down();
            case UNKNOWN, NOT_CONFIGURED -> Health.unknown();
        };
        builder.withDetail("state", state.availability().name());
        builder.withDetail(
                "configured",
                state.availability() != LiveSkladAvailability.NOT_CONFIGURED
        );
        if (state.checkedAt() != null) {
            builder.withDetail("checkedAt", state.checkedAt());
        }
        return builder.build();
    }
}
