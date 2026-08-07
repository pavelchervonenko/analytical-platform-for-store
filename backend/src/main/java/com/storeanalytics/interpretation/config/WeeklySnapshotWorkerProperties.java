package com.storeanalytics.interpretation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.interpretation.snapshot-worker")
public record WeeklySnapshotWorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5s") Duration workerDelay,
        @DefaultValue("10m") Duration leaseDuration,
        @DefaultValue("1m") Duration heartbeatInterval,
        @DefaultValue("30s") Duration retryInitialDelay,
        @DefaultValue("15m") Duration retryMaxDelay
) {

    public WeeklySnapshotWorkerProperties {
        requirePositive(workerDelay, "workerDelay");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(retryInitialDelay, "retryInitialDelay");
        requirePositive(retryMaxDelay, "retryMaxDelay");
        if (heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException(
                    "snapshot worker heartbeatInterval must be shorter than leaseDuration"
            );
        }
        if (leaseDuration.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
            throw new IllegalArgumentException(
                    "snapshot worker leaseDuration must cover at least two heartbeats"
            );
        }
        if (retryMaxDelay.compareTo(retryInitialDelay) < 0) {
            throw new IllegalArgumentException(
                    "snapshot worker retryMaxDelay must not be shorter"
            );
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "snapshot worker " + field + " must be positive"
            );
        }
    }
}
