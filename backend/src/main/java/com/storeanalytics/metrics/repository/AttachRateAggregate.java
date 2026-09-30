package com.storeanalytics.metrics.repository;

import com.storeanalytics.product.model.AttachDenominatorCode;
import java.math.BigDecimal;

public record AttachRateAggregate(
        String metricCode,
        String numeratorCategoryCode,
        AttachDenominatorCode denominatorCode,
        BigDecimal numeratorReceiptCount,
        BigDecimal denominatorReceiptCount,
        long unmatchedNumeratorItemCount,
        long ambiguousWarrantyItemCount,
        long unknownDeviceConditionItemCount,
        boolean preliminary,
        long unassignedReturnItemCount,
        long unassignedMetricReturnItemCount
) {
    /**
     * Keeps selected-seller quantities, unmatched items and unknown-condition counts intact.
     * Pending warranties and unattributed returns are store-wide possible impact, not seller counts;
     * only affected rates become preliminary.
     */
    public AttachRateAggregate withPotentialStoreAttributionRisk(AttachRateAggregate quality) {
        return new AttachRateAggregate(metricCode, numeratorCategoryCode, denominatorCode,
                numeratorReceiptCount, denominatorReceiptCount, unmatchedNumeratorItemCount,
                quality.ambiguousWarrantyItemCount(), unknownDeviceConditionItemCount,
                preliminary || quality.preliminary() || quality.unassignedMetricReturnItemCount() > 0,
                quality.unassignedReturnItemCount(), quality.unassignedMetricReturnItemCount());
    }

    public AttachRateAggregate withPotentialStoreAttributionRisk(AttachAttributionQuality quality) {
        return new AttachRateAggregate(metricCode, numeratorCategoryCode, denominatorCode,
                numeratorReceiptCount, denominatorReceiptCount, unmatchedNumeratorItemCount,
                quality.pendingWarrantyItemCount(), unknownDeviceConditionItemCount, preliminary || quality.preliminary(),
                quality.unassignedReturnItemCount(), quality.unassignedMetricReturnItemCount());
    }
}
