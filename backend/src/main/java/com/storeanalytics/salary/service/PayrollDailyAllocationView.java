package com.storeanalytics.salary.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollDailyAllocationView(
        UUID id,
        UUID employeeId,
        String employeeName,
        LocalDate workDate,
        BigDecimal workedHours,
        BigDecimal amount
) {
}
