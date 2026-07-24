package com.storeanalytics.salary.web;

import com.storeanalytics.salary.model.PayrollCategoryCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record ProductPayrollCategoryRequest(
        @NotNull PayrollCategoryCode categoryCode,
        @NotNull LocalDate validFrom,
        @NotBlank String reason
) {
}
