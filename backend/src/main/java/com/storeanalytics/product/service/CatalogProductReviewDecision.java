package com.storeanalytics.product.service;

import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.salary.model.PayrollCategoryCode;

public record CatalogProductReviewDecision(
        long expectedProductVersion,
        String analyticsCategoryCode,
        ProductConditionType conditionType,
        PayrollCategoryCode payrollCategoryCode,
        String reason
) { }
