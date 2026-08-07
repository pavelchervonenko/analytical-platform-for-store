package com.storeanalytics.metrics.repository;

import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import java.math.BigDecimal;

public record CategoryKpiAggregate(
        String categoryCode,
        String categoryName,
        AnalyticsCategoryKind categoryKind,
        DeviceFamily deviceFamily,
        boolean categoryActive,
        boolean countsAsPhone,
        boolean countsAsDevice,
        boolean countsAsAdditionalRevenue,
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        long includedItemCount,
        long missingCostItemCount,
        long unexpectedZeroCostItemCount
) implements CategoryMetricValues {
}
