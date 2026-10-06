package com.storeanalytics.common.config;

import java.time.Duration;
import java.time.LocalDate;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.sync.historical-sales")
public record HistoricalSalesRefreshProperties(
        boolean enabled,
        LocalDate startDate,
        int windowMinutes,
        int maxRequestsPerStep,
        int maxRequestsPerDay,
        Duration cycleInterval
) {
    public HistoricalSalesRefreshProperties {
        if (enabled && startDate == null) {
            throw new IllegalArgumentException("historical sales startDate is required when enabled");
        }
        if (windowMinutes < 15 || windowMinutes > 180) {
            throw new IllegalArgumentException("historical sales windowMinutes must be 15..180");
        }
        if (maxRequestsPerStep < 1 || maxRequestsPerStep > 100
                || maxRequestsPerDay < maxRequestsPerStep || maxRequestsPerDay > 200) {
            throw new IllegalArgumentException("historical sales request limits are invalid");
        }
        if (cycleInterval == null || cycleInterval.compareTo(Duration.ofHours(1)) < 0
                || cycleInterval.compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException("historical sales cycleInterval must be 1 hour..31 days");
        }
    }
}
