package com.storeanalytics.quality.service;

import com.storeanalytics.salary.model.PayrollRunStatus;
import com.storeanalytics.salary.service.PayrollFreshnessView;
import com.storeanalytics.salary.service.PayrollReadinessStatus;

public record PeriodPayrollQualityView(
        PayrollReadinessStatus readinessStatus,
        boolean canCalculate,
        boolean canApprove,
        boolean planPresent,
        boolean schemePresent,
        int salesDayCount,
        int scheduledDayCount,
        int unmappedItemCount,
        int missingCostItemCount,
        int daysWithoutShift,
        boolean calculated,
        PayrollRunStatus runStatus,
        PayrollFreshnessView freshness
) {
}
