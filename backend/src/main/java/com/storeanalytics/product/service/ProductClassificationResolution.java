package com.storeanalytics.product.service;

import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.model.ProductConditionType;

public record ProductClassificationResolution(
        AnalyticsCategory category,
        ProductCategoryAssignment assignment,
        String version,
        ProductConditionType conditionType
) {
}
