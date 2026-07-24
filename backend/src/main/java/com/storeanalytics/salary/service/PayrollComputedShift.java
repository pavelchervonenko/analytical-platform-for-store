package com.storeanalytics.salary.service;

import com.storeanalytics.employee.model.Employee;
import java.math.BigDecimal;

record PayrollComputedShift(Employee employee, BigDecimal workedHours) {
}
