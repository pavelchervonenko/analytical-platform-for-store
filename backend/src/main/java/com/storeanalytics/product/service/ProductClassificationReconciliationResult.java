package com.storeanalytics.product.service;

import java.util.Set;
import java.util.UUID;

public record ProductClassificationReconciliationResult(
        int inspectedItems,
        int reclassifiedItems,
        int unresolvedItems,
        int resolvedQualityIssues,
        Set<UUID> affectedStoreIds
) {

    public ProductClassificationReconciliationResult(
            int inspectedItems,
            int reclassifiedItems,
            int unresolvedItems,
            int resolvedQualityIssues
    ) {
        this(inspectedItems, reclassifiedItems, unresolvedItems, resolvedQualityIssues, Set.of());
    }

    public ProductClassificationReconciliationResult {
        affectedStoreIds = Set.copyOf(affectedStoreIds);
    }
}
