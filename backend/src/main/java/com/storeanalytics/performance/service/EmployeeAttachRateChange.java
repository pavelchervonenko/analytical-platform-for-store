package com.storeanalytics.performance.service;

import java.math.BigDecimal;

public record EmployeeAttachRateChange(
        String metricCode,
        BigDecimal previousRate,
        BigDecimal currentRate,
        BigDecimal change
) {
}
