package com.storeanalytics.quality.service;

import com.storeanalytics.performance.service.EmployeeRatingHistoryStatus;

public record PeriodRatingQualityView(
        boolean planCoverageComplete,
        int employeeCount,
        int eligibleEmployeeCount,
        int employeeWithShiftCount,
        int rankedEmployeeCount,
        int salesWithoutShiftCount,
        int insufficientScoreCoverageCount,
        EmployeeRatingHistoryStatus historyStatus,
        String formulaVersion
) {
}
