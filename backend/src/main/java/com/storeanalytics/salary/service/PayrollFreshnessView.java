package com.storeanalytics.salary.service;

import java.time.Instant;
import java.util.List;

public record PayrollFreshnessView(
        PayrollFreshnessStatus status,
        boolean requiresRecalculation,
        List<PayrollStaleReason> reasons,
        Instant checkedAt
) {
}
