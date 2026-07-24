package com.storeanalytics.performance.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record StorePerformancePlanRequest(
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2)
        BigDecimal revenueTarget,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2)
        BigDecimal accessoryShareTarget,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2)
        BigDecimal serviceShareTarget,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2)
        BigDecimal additionalShareTarget
) {
}
