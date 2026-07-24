package com.storeanalytics.metrics.service;

import com.storeanalytics.product.model.AttachDenominatorCode;
import java.math.BigDecimal;

public record AttachRateEntry(
        String metricCode,
        String numeratorCategoryCode,
        AttachDenominatorCode denominatorCode,
        BigDecimal numeratorQuantity,
        BigDecimal denominatorQuantity,
        BigDecimal ratePerHundred
) {
}
