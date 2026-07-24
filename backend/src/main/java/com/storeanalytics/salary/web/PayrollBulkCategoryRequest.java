package com.storeanalytics.salary.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

public record PayrollBulkCategoryRequest(
        @NotNull LocalDate validFrom,
        @NotBlank String reason,
        @NotEmpty List<@Valid PayrollBulkCategoryItemRequest> assignments
) {
}
