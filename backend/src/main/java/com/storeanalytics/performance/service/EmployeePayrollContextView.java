package com.storeanalytics.performance.service;

import com.storeanalytics.salary.service.PayrollRunSummaryView;
import com.storeanalytics.salary.service.PayrollStatementView;

public record EmployeePayrollContextView(
        PayrollRunSummaryView run,
        PayrollStatementView statement
) {
}
