package com.storeanalytics.sales.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.product.model.Product;

public record SalesItemIdentity(
        SalesDocument salesDocument,
        String externalId,
        SalesDocumentItem originalItem,
        Product product
) {

    public SalesItemIdentity {
        salesDocument = requireNonNull(salesDocument, "salesDocument");
        externalId = requireText(externalId, "externalId");
        product = requireNonNull(product, "product");
    }
}
