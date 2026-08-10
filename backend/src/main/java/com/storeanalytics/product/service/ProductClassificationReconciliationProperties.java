package com.storeanalytics.product.service;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.product-classification.reconciliation")
public record ProductClassificationReconciliationProperties(
        boolean enabled,
        Set<String> externalProductIds,
        int expectedItemCount
) {

    public ProductClassificationReconciliationProperties {
        externalProductIds = externalProductIds == null
                ? Set.of()
                : Set.copyOf(externalProductIds);
    }
}
