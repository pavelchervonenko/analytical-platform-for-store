package com.storeanalytics.salary.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PayrollSchemeRequest(
        @NotBlank String code,
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        BigDecimal achievedPercentage,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        BigDecimal missedPercentage,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2)
        BigDecimal achievedTier1Rate,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2)
        BigDecimal missedTier1Rate,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2)
        BigDecimal achievedTier2Rate,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2)
        BigDecimal missedTier2Rate,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2)
        BigDecimal advanceAmount
) {
}
