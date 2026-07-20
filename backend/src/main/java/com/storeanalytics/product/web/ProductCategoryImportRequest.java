package com.storeanalytics.product.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

record ProductCategoryImportRequest(
        @NotNull Instant validFrom,
        @NotBlank String ruleVersion,
        @Size(max = 2_000) String changeReason,
        @NotEmpty @Size(max = 10_000) List<@Valid ProductCategoryImportItemRequest> assignments
) {
}
