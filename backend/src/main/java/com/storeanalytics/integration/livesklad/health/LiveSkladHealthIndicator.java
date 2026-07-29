package com.storeanalytics.integration.livesklad.health;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
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
