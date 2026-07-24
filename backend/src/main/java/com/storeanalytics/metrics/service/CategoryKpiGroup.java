package com.storeanalytics.metrics.service;

public record CategoryKpiGroup(
        String groupCode,
        String groupName,
        CategoryKpiMetrics metrics
) {
}
