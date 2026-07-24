package com.storeanalytics.common.observability;

import org.flywaydb.core.Flyway;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class FlywayReadinessHealthIndicator implements HealthIndicator {

    private final Flyway flyway;

    public FlywayReadinessHealthIndicator(Flyway flyway) {
        this.flyway = flyway;
    }

    @Override
    public Health health() {
        try {
            flyway.validate();
            int pending = flyway.info().pending().length;
            if (pending > 0) {
                return Health.down()
                        .withDetail("reason", "PENDING_MIGRATIONS")
                        .withDetail("pending", pending)
                        .build();
            }
            return Health.up()
                    .withDetail("pending", 0)
                    .build();
        } catch (RuntimeException exception) {
            return Health.down()
                    .withDetail("reason", "FLYWAY_VALIDATION_FAILED")
                    .build();
        }
    }
}
