package com.storeanalytics.metrics.repository;

import com.storeanalytics.product.service.CatalogCategoryRegistry;
import java.util.List;

/** Financial breakdowns include retired categories and UNMAPPED, but never EXCLUDE. */
final class CatalogMetricTestCategories {
    private CatalogMetricTestCategories() { }

    static List<String> expectedCodes() {
        return CatalogCategoryRegistry.standard().definitions().stream()
                .filter(category -> category.scope() != CatalogCategoryRegistry.Scope.DEFERRED)
                .map(CatalogCategoryRegistry.Definition::code)
                .filter(code -> !"EXCLUDE".equals(code))
                .toList();
    }
}
