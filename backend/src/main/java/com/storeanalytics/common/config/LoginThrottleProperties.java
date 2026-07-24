package com.storeanalytics.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security.login-throttle")
public record LoginThrottleProperties(
        int emailMaxFailures,
        int ipMaxFailures,
        Duration window,
        Duration blockDuration,
        Duration retention,
        String cleanupCron
) {

    public LoginThrottleProperties {
        if (emailMaxFailures < 2 || ipMaxFailures < emailMaxFailures) {
            throw new IllegalArgumentException("Login throttle limits are invalid");
        }
        requirePositive(window, "Login failure window");
        requirePositive(blockDuration, "Login block duration");
        requirePositive(retention, "Login throttle retention");
        if (cleanupCron == null || cleanupCron.isBlank()) {
            throw new IllegalArgumentException("Login throttle cleanup cron is required");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
