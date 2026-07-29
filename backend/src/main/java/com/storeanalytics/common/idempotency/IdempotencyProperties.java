package com.storeanalytics.common.idempotency;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(
        @NotNull Duration ttl,
        @Min(1) @Max(10_000) int cleanupBatchSize
) {

    public IdempotencyProperties {
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("idempotency ttl must be positive");
        }
    }
}
