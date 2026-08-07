package com.storeanalytics.interpretation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.interpretation.snapshot-planner")
public record WeeklySnapshotPlannerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1m") Duration scanDelay,
        @DefaultValue("72h") Duration revisionWindow,
        @DefaultValue("25") int batchSize,
        @DefaultValue("5") int maxAttempts
) {

    public WeeklySnapshotPlannerProperties {
        requirePositive(scanDelay, "scanDelay");
        requirePositive(revisionWindow, "revisionWindow");
        if (revisionWindow.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException(
                    "snapshot planner revisionWindow must not exceed 7 days"
            );
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "snapshot planner batchSize must be between 1 and 100"
            );
        }
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException(
                    "snapshot planner maxAttempts must be between 1 and 20"
            );
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "snapshot planner " + field + " must be positive"
            );
        }
    }
}
