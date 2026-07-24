package com.storeanalytics.performance.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record WorkScheduleShiftRequest(
        @NotNull UUID employeeId,
        @NotNull
        @DecimalMin("0.01")
        @DecimalMax("11.00")
        @Digits(integer = 2, fraction = 2)
        BigDecimal workedHours
) {
}
