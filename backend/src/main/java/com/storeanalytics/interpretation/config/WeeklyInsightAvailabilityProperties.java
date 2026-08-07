package com.storeanalytics.interpretation.config;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.interpretation.availability")
public record WeeklyInsightAvailabilityProperties(
        @DefaultValue("5m") Duration preparationSla,
        @DefaultValue("15s") Duration refreshInterval,
        @DefaultValue("08:00") LocalTime targetReadyTime
) {

    public WeeklyInsightAvailabilityProperties {
        requirePositive(preparationSla, "preparationSla");
        require(preparationSla.compareTo(Duration.ofHours(1)) <= 0,
                "preparationSla must not exceed one hour");
        requirePositive(refreshInterval, "refreshInterval");
        require(refreshInterval.compareTo(Duration.ofMinutes(5)) <= 0,
                "refreshInterval must not exceed five minutes");
        requireNonNull(targetReadyTime, "targetReadyTime");
    }

    private static void requirePositive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isNegative() && !duration.isZero(),
                field + " must be positive");
    }
}
