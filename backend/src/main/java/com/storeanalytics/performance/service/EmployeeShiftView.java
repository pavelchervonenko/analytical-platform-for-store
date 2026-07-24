package com.storeanalytics.performance.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record EmployeeShiftView(
        UUID id,
        UUID employeeId,
        String employeeName,
        LocalDate workDate,
        BigDecimal workedHours,
        boolean active,
        long version
) {
}
