package com.storeanalytics.salary.repository;

import com.storeanalytics.salary.model.PayrollCategoryCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollMissingCostIssue(
        LocalDate payrollDate,
        UUID documentId,
        String documentExternalId,
        boolean returnDocument,
        UUID productId,
        String productName,
        PayrollCategoryCode payrollCategoryCode,
        BigDecimal quantity,
        BigDecimal netAmount
) {
}
