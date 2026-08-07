package com.storeanalytics.interpretation.config;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.interpretation.generation-planner")
public record LlmAnalysisPlannerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1m") Duration scanDelay,
        @DefaultValue("25") int batchSize,
        @DefaultValue("5m") Duration jobDeadline
) {

    private static final Duration MINIMUM_DEADLINE = Duration.ofMinutes(2);
    private static final Duration MAXIMUM_DEADLINE = Duration.ofMinutes(30);

    public LlmAnalysisPlannerProperties {
        requirePositive(scanDelay, "scanDelay");
        require(batchSize >= 1 && batchSize <= 100,
                "generation planner batchSize must be between 1 and 100");
        requirePositive(jobDeadline, "jobDeadline");
        require(jobDeadline.compareTo(MINIMUM_DEADLINE) >= 0,
                "generation planner jobDeadline must be at least 2 minutes");
        require(jobDeadline.compareTo(MAXIMUM_DEADLINE) <= 0,
                "generation planner jobDeadline must not exceed 30 minutes");
    }

    private static void requirePositive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(),
                "generation planner " + field + " must be positive");
    }
}
