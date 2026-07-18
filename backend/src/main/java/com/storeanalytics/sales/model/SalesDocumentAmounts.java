package com.storeanalytics.sales.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requireNullableNonNegative;

import java.math.BigDecimal;

public record SalesDocumentAmounts(BigDecimal netAmount, BigDecimal costAmount) {

    public SalesDocumentAmounts {
        netAmount = requireNonNegative(netAmount, "netAmount", 19, 2);
        costAmount = requireNullableNonNegative(costAmount, "costAmount", 19, 2);
    }
}
