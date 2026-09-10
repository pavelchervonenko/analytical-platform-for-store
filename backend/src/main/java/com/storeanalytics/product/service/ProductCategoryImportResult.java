package com.storeanalytics.product.service;

import java.util.Set;
import java.util.UUID;

public record ProductCategoryImportResult(
        int requested,
        int productsCreated,
        int assignmentsCreated,
        int assignmentsUnchanged,
        Set<UUID> affectedStoreIds
) {

    public ProductCategoryImportResult(
            int requested,
            int productsCreated,
            int assignmentsCreated,
            int assignmentsUnchanged
    ) {
        this(requested, productsCreated, assignmentsCreated, assignmentsUnchanged, Set.of());
    }

    public ProductCategoryImportResult {
        affectedStoreIds = Set.copyOf(affectedStoreIds);
    }
}
