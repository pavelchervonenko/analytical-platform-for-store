package com.storeanalytics.product.service;

public record ProductCategoryImportResult(
        int requested,
        int productsCreated,
        int assignmentsCreated,
        int assignmentsUnchanged
) {
}
