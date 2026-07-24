package com.storeanalytics.metrics.repository;

import com.storeanalytics.product.model.AttachDenominatorCode;
import java.math.BigDecimal;

public record AttachRateAggregate(
        String metricCode,
        String numeratorCategoryCode,
        AttachDenominatorCode denominatorCode,
        BigDecimal numeratorQuantity,
        BigDecimal denominatorQuantity,
        long unmatchedNumeratorItemCount,
        long ambiguousWarrantyItemCount,
        long unknownDeviceConditionItemCount
) {
}
