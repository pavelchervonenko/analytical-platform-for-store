package com.storeanalytics.report.service;

import com.storeanalytics.salary.model.PayrollSourceFingerprint;
import java.util.UUID;

record MonthlyReportSource(
        UUID payrollRunId,
        int payrollRevision,
        PayrollSourceFingerprint payrollSourceFingerprint,
        String ratingFormulaVersion,
        String storeKpiFormulaVersion,
        String categoryKpiFormulaVersion,
        String averageKpiFormulaVersion,
        String attachRateFormulaVersion
) {
}
