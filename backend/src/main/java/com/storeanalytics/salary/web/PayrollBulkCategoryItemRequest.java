package com.storeanalytics.salary.web;

import com.storeanalytics.salary.model.PayrollCategoryCode;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PayrollBulkCategoryItemRequest(
        @NotNull UUID productId,
        @NotNull PayrollCategoryCode categoryCode
) {
}
