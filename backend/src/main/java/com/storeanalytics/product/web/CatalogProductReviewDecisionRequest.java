package com.storeanalytics.product.web;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.salary.model.PayrollCategoryCode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CatalogProductReviewDecisionRequest(
        @NotNull @Min(0) Long expectedProductVersion,
        @NotBlank String analyticsCategoryCode,
        @NotNull ProductConditionType conditionType,
        @NotNull PayrollCategoryCode payrollCategoryCode,
        @NotBlank @Size(max = 500) String reason
) { }
