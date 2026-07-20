package com.storeanalytics.product.web;

import com.storeanalytics.product.model.ProductConditionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

record ProductCategoryImportItemRequest(
        @NotBlank String externalProductId,
        @NotBlank String productName,
        @NotBlank String categoryCode,
        @NotNull ProductConditionType conditionType
) {
}
