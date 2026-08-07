package com.storeanalytics.metrics.service;

public record EmployeeCategoryKpiGroup(
        String groupCode,
        String groupName,
        EmployeeCategoryKpiMetrics metrics
) {
}
