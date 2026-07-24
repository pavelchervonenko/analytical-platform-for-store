package com.storeanalytics.metrics.service;

import java.math.BigDecimal;

public record AverageMetricComparison(
        AverageMetricSnapshot current,
        AverageMetricSnapshot previous,
        BigDecimal changePercent
) {
}
