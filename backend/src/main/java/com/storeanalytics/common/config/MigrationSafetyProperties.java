package com.storeanalytics.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.migration")
public record MigrationSafetyProperties(
        Duration lockTimeout,
        Duration statementTimeout,
        int lockRetryCount
) {

    public MigrationSafetyProperties {
        requireRange(lockTimeout, Duration.ofMillis(100), Duration.ofMinutes(1),
                "lockTimeout");
        requireRange(statementTimeout, Duration.ofSeconds(1), Duration.ofHours(1),
                "statementTimeout");
        if (statementTimeout.compareTo(lockTimeout) <= 0) {
            throw new IllegalArgumentException(
                    "statementTimeout must exceed lockTimeout"
            );
        }
        if (lockRetryCount < 0 || lockRetryCount > 100) {
            throw new IllegalArgumentException(
                    "lockRetryCount must be between 0 and 100"
            );
        }
    }

    public String connectionInitSql() {
        return "SELECT set_config('lock_timeout', '"
                + lockTimeout.toMillis()
                + "ms', false), set_config('statement_timeout', '"
                + statementTimeout.toMillis()
                + "ms', false)";
    }

    private static void requireRange(
            Duration value,
            Duration minimum,
            Duration maximum,
            String field
    ) {
        if (value == null || value.compareTo(minimum) < 0
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " must be between " + minimum + " and " + maximum
            );
        }
    }
}
