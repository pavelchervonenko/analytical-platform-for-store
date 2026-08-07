package com.storeanalytics.interpretation.config;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.interpretation.generation-worker")
public record LlmAnalysisWorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5s") Duration workerDelay,
        @DefaultValue("2m") Duration leaseDuration,
        @DefaultValue("15s") Duration heartbeatInterval,
        @DefaultValue("30s") Duration recoveryDelay,
        @DefaultValue("90s") Duration providerCallTimeout,
        @DefaultValue("524288") int maxRequestBytes,
        @DefaultValue("50.00") BigDecimal maxEstimatedCostRub
) {

    public LlmAnalysisWorkerProperties {
        requirePositive(workerDelay, "workerDelay");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(recoveryDelay, "recoveryDelay");
        requirePositive(providerCallTimeout, "providerCallTimeout");
        require(heartbeatInterval.compareTo(leaseDuration) < 0,
                "generation worker heartbeatInterval must be shorter than leaseDuration");
        require(providerCallTimeout.compareTo(Duration.ofMinutes(10)) <= 0,
                "generation worker providerCallTimeout must not exceed 10 minutes");
        require(maxRequestBytes >= 16_384 && maxRequestBytes <= 1_048_576,
                "generation worker maxRequestBytes must be between 16384 and 1048576");
        requireNonNull(maxEstimatedCostRub, "maxEstimatedCostRub");
        require(maxEstimatedCostRub.compareTo(BigDecimal.ZERO) > 0,
                "generation worker maxEstimatedCostRub must be positive");
        require(maxEstimatedCostRub.compareTo(new BigDecimal("1000.00")) <= 0,
                "generation worker maxEstimatedCostRub must not exceed 1000");
    }

    private static void requirePositive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(),
                "generation worker " + field + " must be positive");
    }
}
