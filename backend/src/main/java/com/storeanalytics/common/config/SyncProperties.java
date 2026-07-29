package com.storeanalytics.common.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.sync")
public record SyncProperties(
        Duration windowSize,
        int maxAttempts,
        Duration leaseDuration,
        Duration retryInitialDelay,
        Duration retryMaxDelay,
        Duration retryAbsoluteMaxDelay,
        int incrementalOverlapDays,
        int maximumBackfillDays,
        ZoneId reportingZone
) {
    public SyncProperties {
        java.util.Objects.requireNonNull(windowSize, "windowSize");
        java.util.Objects.requireNonNull(leaseDuration, "leaseDuration");
        java.util.Objects.requireNonNull(retryInitialDelay, "retryInitialDelay");
        java.util.Objects.requireNonNull(retryMaxDelay, "retryMaxDelay");
        java.util.Objects.requireNonNull(retryAbsoluteMaxDelay, "retryAbsoluteMaxDelay");
        java.util.Objects.requireNonNull(reportingZone, "reportingZone");
        if (windowSize.compareTo(Duration.ofMinutes(15)) < 0
                || windowSize.compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException(
                    "sync windowSize must be between 15 minutes and 31 days"
            );
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("sync maxAttempts must be between 1 and 20");
        }
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("sync leaseDuration must be positive");
        }
        if (retryInitialDelay.isZero() || retryInitialDelay.isNegative()
                || retryMaxDelay.compareTo(retryInitialDelay) < 0) {
            throw new IllegalArgumentException("sync retry delays are invalid");
        }
        if (retryAbsoluteMaxDelay.compareTo(retryMaxDelay) < 0
                || retryAbsoluteMaxDelay.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException(
                    "sync retryAbsoluteMaxDelay must be between retryMaxDelay and 7 days"
            );
        }
        if (incrementalOverlapDays < 1 || incrementalOverlapDays > 31) {
            throw new IllegalArgumentException(
                    "sync incrementalOverlapDays must be between 1 and 31"
            );
        }
        if (maximumBackfillDays < 1) {
            throw new IllegalArgumentException("sync maximumBackfillDays must be positive");
        }
    }
}
