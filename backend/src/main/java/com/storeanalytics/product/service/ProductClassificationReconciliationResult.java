package com.storeanalytics.product.service;

public record ProductClassificationReconciliationResult(
        int inspectedItems,
        int reclassifiedItems,
        int unresolvedItems,
        int resolvedQualityIssues
) {
}
