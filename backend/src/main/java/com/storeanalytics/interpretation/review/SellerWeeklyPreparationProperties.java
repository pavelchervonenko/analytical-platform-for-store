package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Independent opt-in for free history preparation; never authorizes provider calls or a baseline. */
@ConfigurationProperties("app.interpretation.seller-weekly-preparation")
public record SellerWeeklyPreparationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("1m") Duration scanDelay,
        @DefaultValue("10") int storeBatchSize,
        @DefaultValue("4") int discoveryWeeks,
        @DefaultValue("25") int refreshBatchSize,
        @DefaultValue("2") int preparationBatchSize,
        @DefaultValue("1m") Duration timeBudget
) {
    public SellerWeeklyPreparationProperties {
        requireNonNull(scanDelay, "scanDelay");
        require(scanDelay.compareTo(Duration.ofSeconds(10)) >= 0
                && scanDelay.compareTo(Duration.ofHours(1)) <= 0, "scanDelay must be between 10 seconds and 1 hour");
        require(storeBatchSize >= 1 && storeBatchSize <= 100, "storeBatchSize must be between 1 and 100");
        require(discoveryWeeks >= 1 && discoveryWeeks <= 52, "discoveryWeeks must be between 1 and 52");
        require(refreshBatchSize >= 1 && refreshBatchSize <= 100, "refreshBatchSize must be between 1 and 100");
        require(preparationBatchSize >= 1 && preparationBatchSize <= 10,
                "preparationBatchSize must be between 1 and 10");
        requireNonNull(timeBudget, "timeBudget");
        require(timeBudget.compareTo(Duration.ofSeconds(1)) >= 0
                && timeBudget.compareTo(Duration.ofMinutes(5)) <= 0,
                "timeBudget must be between 1 second and 5 minutes");
    }
}
