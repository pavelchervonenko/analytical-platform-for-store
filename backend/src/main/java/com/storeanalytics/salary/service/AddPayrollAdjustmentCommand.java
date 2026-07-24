package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollAdjustmentType;
import java.math.BigDecimal;
import java.util.UUID;

public record AddPayrollAdjustmentCommand(
        UUID storeId,
        UUID runId,
        UUID employeeId,
        PayrollAdjustmentType type,
        BigDecimal amount,
        String reason,
        long version,
        UUID actorId
) {
}
