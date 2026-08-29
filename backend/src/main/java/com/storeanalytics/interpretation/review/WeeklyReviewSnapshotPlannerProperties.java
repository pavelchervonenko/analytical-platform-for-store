package com.storeanalytics.interpretation.review;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(
        "app.interpretation.weekly-review-snapshot-planner"
)
public record WeeklyReviewSnapshotPlannerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5m") Duration scanDelay,
        @DefaultValue("25") int batchSize
) {

    public WeeklyReviewSnapshotPlannerProperties {
        if (scanDelay == null || scanDelay.isZero() || scanDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "weekly review snapshot planner scanDelay must be positive"
            );
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException(
                    "weekly review snapshot planner batchSize must be between 1 and 100"
            );
        }
    }
}
