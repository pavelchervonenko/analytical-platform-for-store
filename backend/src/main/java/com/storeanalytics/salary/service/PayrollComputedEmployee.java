package com.storeanalytics.salary.service;

import com.storeanalytics.employee.model.Employee;
import java.math.BigDecimal;

record PayrollComputedEmployee(
        Employee employee,
        int shiftCount,
        BigDecimal workedHours,
        BigDecimal earnedAmount
) {
}
