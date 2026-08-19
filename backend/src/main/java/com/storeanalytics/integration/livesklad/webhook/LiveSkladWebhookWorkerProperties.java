package com.storeanalytics.integration.livesklad.webhook;

import static com.storeanalytics.common.validation.ModelValidation.require;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.livesklad.webhook.worker")
public record LiveSkladWebhookWorkerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("5s") Duration delay,
        @DefaultValue("2m") Duration leaseDuration,
        @DefaultValue("30s") Duration retryInitialDelay,
        @DefaultValue("15m") Duration retryMaxDelay,
        @DefaultValue("8") int maxAttempts
) {

    public LiveSkladWebhookWorkerProperties {
        requirePositive(delay, "delay");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(retryInitialDelay, "retryInitialDelay");
        requirePositive(retryMaxDelay, "retryMaxDelay");
        require(!retryMaxDelay.minus(retryInitialDelay).isNegative(),
                "retryMaxDelay must not be shorter than retryInitialDelay");
        require(maxAttempts >= 1 && maxAttempts <= 100,
                "maxAttempts must be between 1 and 100");
    }

    private static void requirePositive(Duration value, String field) {
        require(value != null && !value.isZero() && !value.isNegative(),
                field + " must be positive");
    }
}
