package com.storeanalytics.report.service;

import java.math.BigDecimal;

public record AnnualAttachRateTotals(
        String metricCode,
        BigDecimal numeratorQuantity,
        BigDecimal denominatorQuantity,
        BigDecimal ratePerHundred
) {
}
