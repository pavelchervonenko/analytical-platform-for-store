package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollPlanResult;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollPreviewView(
        UUID storeId,
        LocalDate periodMonth,
        boolean persisted,
        PayrollPlanResult planResult,
        PayrollSchemeView scheme,
        PayrollReadinessView readiness,
        PayrollScenarioView actualScenario
) {
}
