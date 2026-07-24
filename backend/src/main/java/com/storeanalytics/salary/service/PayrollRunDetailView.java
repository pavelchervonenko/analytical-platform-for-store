package com.storeanalytics.salary.service;

import java.util.List;

public record PayrollRunDetailView(
        PayrollRunSummaryView run,
        PayrollSchemeView scheme,
        List<PayrollDailyPoolView> dailyPools,
        List<PayrollDailyAllocationView> dailyAllocations,
        List<PayrollAdjustmentView> adjustments,
        List<PayrollStatementView> statements,
        List<PayrollEventView> events
) {
}
