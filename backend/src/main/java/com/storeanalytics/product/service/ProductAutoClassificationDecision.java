package com.storeanalytics.product.service;

import com.storeanalytics.product.model.ProductConditionType;

public record ProductAutoClassificationDecision(
        String categoryCode,
        ProductConditionType conditionType,
        String ruleId
) {

    public ProductAutoClassificationDecision {
        // Unknown codes must fail before a new sale can use a misspelled category.
        // Membership is not permission to activate a new category or rewrite history.
        CatalogCategoryRegistry.standard().require(categoryCode);
    }
}
