package com.storeanalytics.metrics.service;

import java.math.BigDecimal;

public record OverviewCommercialMetric(
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal sharePercent
) {
}
