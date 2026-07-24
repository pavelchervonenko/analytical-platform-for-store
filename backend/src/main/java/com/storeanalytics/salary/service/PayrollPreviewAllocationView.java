package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.util.UUID;

public record PayrollPreviewAllocationView(
        UUID employeeId,
        String employeeName,
        BigDecimal workedHours,
        BigDecimal amount
) {
}
