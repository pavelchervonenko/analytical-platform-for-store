package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollAdjustmentType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PayrollAdjustmentView(
        UUID id,
        UUID employeeId,
        String employeeName,
        PayrollAdjustmentType type,
        BigDecimal amount,
        String reason,
        boolean active,
        UUID createdBy,
        UUID voidedBy,
        String voidReason,
        Instant voidedAt,
        long version,
        Instant createdAt
) {
}
