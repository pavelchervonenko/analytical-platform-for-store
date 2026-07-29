package com.storeanalytics.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.reports.backfill")
public record ReportBackfillProperties(
        int maxAttempts,
        Duration leaseDuration,
        Duration retryInitialDelay,
        Duration retryMaxDelay,
        int maxActiveJobs
) {

    public ReportBackfillProperties {
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw new IllegalArgumentException(
                    "report backfill maxAttempts must be between 1 and 10"
            );
        }
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(retryInitialDelay, "retryInitialDelay");
        requirePositive(retryMaxDelay, "retryMaxDelay");
        if (retryMaxDelay.compareTo(retryInitialDelay) < 0) {
            throw new IllegalArgumentException(
                    "report backfill retryMaxDelay must not be shorter"
            );
        }
        if (maxActiveJobs < 1 || maxActiveJobs > 100) {
            throw new IllegalArgumentException(
                    "report backfill maxActiveJobs must be between 1 and 100"
            );
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    "report backfill " + field + " must be positive"
            );
        }
    }
}
