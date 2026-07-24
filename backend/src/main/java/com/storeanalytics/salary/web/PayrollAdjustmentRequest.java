package com.storeanalytics.salary.web;

import com.storeanalytics.salary.model.PayrollAdjustmentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record PayrollAdjustmentRequest(
        @NotNull UUID employeeId,
        @NotNull PayrollAdjustmentType type,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 17, fraction = 2)
        BigDecimal amount,
        @NotBlank String reason,
        long runVersion
) {
}
