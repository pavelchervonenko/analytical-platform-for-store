package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollAppliedRates;
import com.storeanalytics.salary.model.PayrollPlanResult;
import com.storeanalytics.salary.model.PayrollRunQuality;
import java.util.List;

record PayrollComputationResult(
        PayrollPlanResult planResult,
        PayrollAppliedRates appliedRates,
        PayrollRunQuality quality,
        List<PayrollComputedDay> days,
        List<PayrollComputedEmployee> employees
) {
}
