package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollPlanResult;
import com.storeanalytics.salary.repository.PayrollMissingCostIssue;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayrollReadinessView(
        UUID storeId,
        LocalDate periodMonth,
        PayrollReadinessStatus status,
        boolean canCalculate,
        boolean canApprove,
        boolean planPresent,
        boolean schemePresent,
        PayrollPlanResult planResult,
        int salesDayCount,
        int scheduledDayCount,
        int unmappedItemCount,
        int missingCostItemCount,
        int daysWithoutShift,
        List<PayrollUnmappedProductView> unmappedProducts,
        List<PayrollMissingCostIssue> missingCosts,
        List<PayrollShiftIssueView> shiftIssues
) {
}
