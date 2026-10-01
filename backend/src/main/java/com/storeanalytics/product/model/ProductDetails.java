package com.storeanalytics.product.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;

public record ProductDetails(
        SourceProductGroup sourceGroup,
        String code,
        String sku,
        String name,
        ProductSourceKind sourceKind,
        Instant sourceUpdatedAt,
        boolean sourceGroupObserved
) {

    /**
     * Document positions do not carry a group: null means unobserved, not cleared.
     * Explicit authoritative absence requires the canonical constructor with observed=true.
     */
    public ProductDetails(
            SourceProductGroup sourceGroup,
            String code,
            String sku,
            String name,
            ProductSourceKind sourceKind,
            Instant sourceUpdatedAt
    ) {
        this(sourceGroup, code, sku, name, sourceKind, sourceUpdatedAt, sourceGroup != null);
    }

    public ProductDetails {
        require(sourceGroupObserved || sourceGroup == null,
                "an unobserved source group must be null");
        name = requireText(name, "name");
        sourceKind = requireNonNull(sourceKind, "sourceKind");
    }
}
