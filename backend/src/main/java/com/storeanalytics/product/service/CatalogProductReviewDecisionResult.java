package com.storeanalytics.product.service;

import java.util.Set;
import java.util.UUID;

public record CatalogProductReviewDecisionResult(
        UUID productId,
        String analyticsCategoryCode,
        String payrollCategoryCode,
        int reclassifiedItems,
        Set<UUID> affectedStoreIds
) {
    public CatalogProductReviewDecisionResult {
        affectedStoreIds = Set.copyOf(affectedStoreIds);
    }
}
