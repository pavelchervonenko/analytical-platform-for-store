package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollCategoryCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ProductPayrollCategoryView(
        UUID id,
        UUID productId,
        String productName,
        PayrollCategoryCode categoryCode,
        LocalDate validFrom,
        LocalDate validTo,
        UUID assignedBy,
        String changeReason,
        long version,
        Instant createdAt
) {
}
