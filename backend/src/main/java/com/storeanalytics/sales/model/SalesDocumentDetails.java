package com.storeanalytics.sales.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Instant;
import java.time.LocalDate;

public record SalesDocumentDetails(
        String documentNumber,
        SalesDocumentKind documentKind,
        String sourceDocumentType,
        String sourceStatus,
        Instant occurredAt,
        LocalDate businessDate,
        Instant sourceUpdatedAt
) {

    public SalesDocumentDetails {
        documentKind = requireNonNull(documentKind, "documentKind");
        sourceDocumentType = requireText(sourceDocumentType, "sourceDocumentType");
        occurredAt = requireNonNull(occurredAt, "occurredAt");
        businessDate = requireNonNull(businessDate, "businessDate");
    }

    public void validateOriginalDocument(SalesDocument originalDocument) {
        require((documentKind == SalesDocumentKind.SALE && originalDocument == null)
                        || (documentKind == SalesDocumentKind.RETURN
                        && (originalDocument == null || originalDocument.isSale())),
                "sale must not reference an original; return may reference only an original sale");
    }
}
