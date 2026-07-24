package com.storeanalytics.report.service;

import com.storeanalytics.metrics.model.ReportType;
import java.time.LocalDate;

record ReportPeriodKey(
        ReportType type,
        LocalDate start,
        LocalDate end
) {
}
