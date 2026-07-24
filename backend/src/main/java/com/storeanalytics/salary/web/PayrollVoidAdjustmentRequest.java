package com.storeanalytics.salary.web;

import jakarta.validation.constraints.NotBlank;

public record PayrollVoidAdjustmentRequest(
        @NotBlank String reason,
        long runVersion,
        long adjustmentVersion
) {
}
