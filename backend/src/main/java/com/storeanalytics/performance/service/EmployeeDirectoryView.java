package com.storeanalytics.performance.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EmployeeDirectoryView(
        UUID storeId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate previousPeriodStart,
        LocalDate previousPeriodEnd,
        List<EmployeeDirectoryEntry> employees
) {
}
