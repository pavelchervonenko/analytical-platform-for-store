package com.storeanalytics.salary.service;

import com.storeanalytics.employee.model.Employee;
import java.math.BigDecimal;

record PayrollComputedAllocation(
        Employee employee,
        BigDecimal workedHours,
        BigDecimal amount
) {
}
