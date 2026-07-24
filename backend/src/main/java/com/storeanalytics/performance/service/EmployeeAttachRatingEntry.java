package com.storeanalytics.performance.service;

import com.storeanalytics.product.model.AttachDenominatorCode;
import java.math.BigDecimal;

public record EmployeeAttachRatingEntry(
        String metricCode,
        String numeratorCategoryCode,
        AttachDenominatorCode denominatorCode,
        BigDecimal numeratorQuantity,
        BigDecimal denominatorQuantity,
        BigDecimal ratePercent,
        BigDecimal storeRatePercent,
        boolean includedInScore,
        BigDecimal score
) {
}
