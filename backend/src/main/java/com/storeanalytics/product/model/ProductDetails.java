package com.storeanalytics.product.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;

public record ProductDetails(
        SourceProductGroup sourceGroup,
        String code,
        String sku,
        String name,
        ProductSourceKind sourceKind,
        Instant sourceUpdatedAt
) {

    public ProductDetails {
        name = requireText(name, "name");
        sourceKind = requireNonNull(sourceKind, "sourceKind");
    }
}
