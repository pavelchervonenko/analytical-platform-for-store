package com.storeanalytics.product.service;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.product.model.ProductConditionType;

public record ProductCategoryImportEntry(
        String externalProductId,
        String productName,
        String categoryCode,
        ProductConditionType conditionType
) {

    public ProductCategoryImportEntry {
        externalProductId = requireText(externalProductId, "externalProductId");
        productName = requireText(productName, "productName");
        categoryCode = requireText(categoryCode, "categoryCode");
        conditionType = requireNonNull(conditionType, "conditionType");
    }
}
