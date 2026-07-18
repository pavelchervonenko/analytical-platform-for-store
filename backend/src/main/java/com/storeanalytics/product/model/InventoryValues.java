package com.storeanalytics.product.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNumeric;
import static com.storeanalytics.common.validation.ModelValidation.requireNullableNonNegative;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryValues(
        BigDecimal quantity,
        BigDecimal retailPrice,
        BigDecimal costAmount,
        Instant sourceUpdatedAt
) {

    public InventoryValues {
        quantity = requireNumeric(quantity, "quantity", 19, 3);
        retailPrice = requireNullableNonNegative(retailPrice, "retailPrice", 19, 2);
        costAmount = requireNullableNonNegative(costAmount, "costAmount", 19, 2);
    }
}
