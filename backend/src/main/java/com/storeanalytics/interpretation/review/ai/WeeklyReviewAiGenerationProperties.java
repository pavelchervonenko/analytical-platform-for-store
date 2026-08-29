package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.interpretation.weekly-review-ai")
public record WeeklyReviewAiGenerationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("false") boolean plannerEnabled,
        @DefaultValue("false") boolean workerEnabled,
        @DefaultValue("YANDEX") String providerCode,
        @DefaultValue("0.1") BigDecimal temperature,
        @DefaultValue("1400") int maxOutputTokens,
        @DefaultValue("2") int maxProviderCalls,
        @DefaultValue("1m") Duration scanDelay,
        @DefaultValue("5s") Duration workerDelay,
        @DefaultValue("4m") Duration leaseDuration,
        @DefaultValue("30s") Duration heartbeatInterval,
        @DefaultValue("180s") Duration providerCallTimeout,
        @DefaultValue("30s") Duration retryInitialDelay,
        @DefaultValue("10m") Duration retryMaxDelay,
        @DefaultValue("2h") Duration jobDeadline,
        @DefaultValue("5m") Duration preparationSla,
        @DefaultValue("10") int batchSize,
        @DefaultValue("131072") int maxRequestBytes,
        @DefaultValue("10.00") BigDecimal maxEstimatedCostRub,
        @DefaultValue("100.00") BigDecimal dailyCostLimitRub
) {

    public WeeklyReviewAiGenerationProperties {
        requireText(providerCode, "providerCode");
        requireNonNull(temperature, "temperature");
        require(temperature.compareTo(BigDecimal.ZERO) >= 0
                        && temperature.compareTo(BigDecimal.ONE) <= 0,
                "weekly review AI temperature must be between 0 and 1");
        require(maxOutputTokens >= 256 && maxOutputTokens <= 4000,
                "weekly review AI maxOutputTokens must be between 256 and 4000");
        require(maxProviderCalls >= 1 && maxProviderCalls <= 2,
                "weekly review AI maxProviderCalls must be 1 or 2");
        requirePositive(scanDelay, "scanDelay");
        requirePositive(workerDelay, "workerDelay");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(heartbeatInterval, "heartbeatInterval");
        requirePositive(providerCallTimeout, "providerCallTimeout");
        requirePositive(retryInitialDelay, "retryInitialDelay");
        requirePositive(retryMaxDelay, "retryMaxDelay");
        requirePositive(jobDeadline, "jobDeadline");
        requirePositive(preparationSla, "preparationSla");
        require(heartbeatInterval.compareTo(leaseDuration) < 0,
                "weekly review AI heartbeat must be shorter than lease");
        require(providerCallTimeout.compareTo(leaseDuration) < 0,
                "weekly review AI provider timeout must be shorter than lease");
        require(retryInitialDelay.compareTo(retryMaxDelay) <= 0,
                "weekly review AI retry delay bounds are invalid");
        require(jobDeadline.compareTo(providerCallTimeout) > 0,
                "weekly review AI job deadline must exceed provider timeout");
        require(batchSize >= 1 && batchSize <= 100,
                "weekly review AI batchSize must be between 1 and 100");
        require(maxRequestBytes >= 16_384 && maxRequestBytes <= 524_288,
                "weekly review AI maxRequestBytes is outside safe bounds");
        requirePositiveMoney(maxEstimatedCostRub, "maxEstimatedCostRub");
        requirePositiveMoney(dailyCostLimitRub, "dailyCostLimitRub");
        require(dailyCostLimitRub.compareTo(maxEstimatedCostRub) >= 0,
                "weekly review AI daily budget must cover one request");
    }

    public Duration retryDelay(int completedAttempts) {
        require(completedAttempts > 0, "completedAttempts must be positive");
        long multiplier = 1L << Math.min(completedAttempts - 1, 10);
        Duration candidate = retryInitialDelay.multipliedBy(multiplier);
        return candidate.compareTo(retryMaxDelay) > 0
                ? retryMaxDelay : candidate;
    }

    private static void requirePositive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(),
                "weekly review AI " + field + " must be positive");
    }

    private static void requirePositiveMoney(BigDecimal value, String field) {
        BigDecimal amount = requireNonNull(value, field);
        require(amount.compareTo(BigDecimal.ZERO) > 0,
                "weekly review AI " + field + " must be positive");
        require(amount.compareTo(new BigDecimal("1000.00")) <= 0,
                "weekly review AI " + field + " exceeds safe bound");
    }
}
