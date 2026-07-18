package com.storeanalytics.sales.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.product.model.AnalyticsCategory;
import com.storeanalytics.product.model.ProductCategoryAssignment;
import com.storeanalytics.product.model.ProductConditionType;

public record SalesItemClassification(
        String productNameSnapshot,
        String sourceGroupNameSnapshot,
        AnalyticsCategory analyticsCategory,
        ProductCategoryAssignment categoryAssignment,
        String classificationVersion,
        ProductConditionType conditionType
) {

    public SalesItemClassification {
        productNameSnapshot = requireText(productNameSnapshot, "productNameSnapshot");
        analyticsCategory = requireNonNull(analyticsCategory, "analyticsCategory");
        conditionType = requireNonNull(conditionType, "conditionType");
    }
}
