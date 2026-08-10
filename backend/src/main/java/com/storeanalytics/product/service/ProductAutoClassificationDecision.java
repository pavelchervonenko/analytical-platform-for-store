package com.storeanalytics.product.service;

import com.storeanalytics.product.model.ProductConditionType;

public record ProductAutoClassificationDecision(
        String categoryCode,
        ProductConditionType conditionType,
        String ruleId
) {
}
