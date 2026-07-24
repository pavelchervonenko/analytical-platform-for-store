package com.storeanalytics.metrics.service;

import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;

public record CategoryKpiEntry(
        String categoryCode,
        String categoryName,
        AnalyticsCategoryKind categoryKind,
        DeviceFamily deviceFamily,
        boolean categoryActive,
        boolean countsAsPhone,
        boolean countsAsDevice,
        boolean countsAsAdditionalRevenue,
        CategoryKpiMetrics metrics
) {
}
