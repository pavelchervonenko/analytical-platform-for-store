package com.storeanalytics.report.service;

import java.util.List;

record AnnualReportSource(
        List<AnnualReportSourceMonth> months
) {
}
