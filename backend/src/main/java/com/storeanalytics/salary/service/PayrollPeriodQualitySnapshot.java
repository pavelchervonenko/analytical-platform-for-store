package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollRunStatus;

public record PayrollPeriodQualitySnapshot(
        PayrollReadinessView readiness,
        boolean calculated,
        PayrollRunStatus runStatus,
        PayrollFreshnessView freshness
) {
}
