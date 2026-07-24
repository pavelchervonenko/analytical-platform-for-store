package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollAppliedRates;
import java.math.BigDecimal;
import java.util.List;

public record PayrollScenarioView(
        PayrollAppliedRates appliedRates,
        boolean calculationComplete,
        BigDecimal totalFundAmount,
        BigDecimal totalPayableAmount,
        List<PayrollPreviewDayView> days,
        List<PayrollPreviewEmployeeView> employees
) {
}
