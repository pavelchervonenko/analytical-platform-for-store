package com.storeanalytics.salary.web;

import com.storeanalytics.salary.service.PayrollBulkClassificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record PayrollBulkCategoryRequest(
        @NotNull LocalDate validFrom,
        @NotBlank @Size(max = PayrollBulkClassificationService.MAXIMUM_REASON_LENGTH)
        String reason,
        @NotEmpty @Size(max = PayrollBulkClassificationService.MAXIMUM_ASSIGNMENTS)
        List<@Valid PayrollBulkCategoryItemRequest> assignments
) {
}
