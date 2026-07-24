package com.storeanalytics.performance.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record RatingSchemeRequest(
        @NotBlank String code,
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2) BigDecimal contributionWeight,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2) BigDecimal efficiencyWeight,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2) BigDecimal structureWeight,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2) BigDecimal attachWeight,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2) BigDecimal accessoryStructureWeight,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2) BigDecimal serviceStructureWeight,
        @NotNull @DecimalMin("0.001") @Digits(integer = 16, fraction = 3)
        BigDecimal minimumAttachDenominator,
        @NotNull @DecimalMin("100.00") @Digits(integer = 4, fraction = 2)
        BigDecimal scoreCap,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00")
        @Digits(integer = 3, fraction = 2) BigDecimal minimumCoveragePercent
) {
}
