package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollCategoryCode;
import java.util.UUID;

public record PayrollProductCategoryChange(
        UUID productId,
        PayrollCategoryCode categoryCode
) {
}
