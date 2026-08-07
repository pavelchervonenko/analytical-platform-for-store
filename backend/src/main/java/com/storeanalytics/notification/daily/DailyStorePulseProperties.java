package com.storeanalytics.notification.daily;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.notification.daily-pulse")
public record DailyStorePulseProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5m") Duration plannerDelay,
        @DefaultValue("08:05") LocalTime sendAfter,
        @DefaultValue("14:00") LocalTime expiresAt,
        @DefaultValue("daily-store-pulse-v1") String policyVersion,
        @DefaultValue("daily-store-pulse-v2") String renderVersion
) {
    public DailyStorePulseProperties {
        if (plannerDelay == null || plannerDelay.isZero() || plannerDelay.isNegative()) {
            throw new IllegalArgumentException("Daily pulse plannerDelay must be positive");
        }
        if (sendAfter == null || expiresAt == null || !expiresAt.isAfter(sendAfter)) {
            throw new IllegalArgumentException("Daily pulse delivery window is invalid");
        }
        if (policyVersion == null || policyVersion.isBlank()
                || renderVersion == null || renderVersion.isBlank()) {
            throw new IllegalArgumentException("Daily pulse versions must not be blank");
        }
    }
}
