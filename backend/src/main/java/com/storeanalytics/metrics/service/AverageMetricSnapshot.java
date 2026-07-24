package com.storeanalytics.metrics.service;

import java.math.BigDecimal;

public record AverageMetricSnapshot(
        BigDecimal numerator,
        BigDecimal denominator,
        BigDecimal value
) {
}
