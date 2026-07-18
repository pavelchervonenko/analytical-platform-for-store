package com.storeanalytics.sales.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requireNullableNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requirePositive;

import java.math.BigDecimal;

public record SalesItemAmounts(
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal grossAmount,
        BigDecimal discountAmount,
        BigDecimal netAmount,
        BigDecimal costAmount
) {

    public SalesItemAmounts {
        quantity = requirePositive(quantity, "quantity", 19, 3);
        unitPrice = requireNonNegative(unitPrice, "unitPrice", 19, 2);
        grossAmount = requireNonNegative(grossAmount, "grossAmount", 19, 2);
        discountAmount = requireNonNegative(discountAmount, "discountAmount", 19, 2);
        netAmount = requireNonNegative(netAmount, "netAmount", 19, 2);
        costAmount = requireNullableNonNegative(costAmount, "costAmount", 19, 2);
    }
}
