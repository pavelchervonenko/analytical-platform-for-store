package com.storeanalytics.product.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.product-classification.reconciliation")
public record ProductClassificationReconciliationProperties(
        boolean enabled,
        UUID connectionId,
        Set<String> externalProductIds,
        int expectedItemCount
) {

    public ProductClassificationReconciliationProperties {
        externalProductIds = externalProductIds == null
                ? Set.of()
                : Set.copyOf(externalProductIds);
    }
}
