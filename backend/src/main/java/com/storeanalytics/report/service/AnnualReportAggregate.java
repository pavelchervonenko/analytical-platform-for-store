package com.storeanalytics.report.service;

import java.util.List;

record AnnualReportAggregate(
        AnnualStoreTotals totals,
        List<AnnualCategoryTotals> categories,
        List<AnnualAttachRateTotals> attachRates,
        List<AnnualEmployeeTotals> employees
) {
}
