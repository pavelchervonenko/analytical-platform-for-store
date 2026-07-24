package com.storeanalytics.metrics.service;

public record AttachRateDataQuality(
        long unmatchedNumeratorItemCount,
        long ambiguousWarrantyItemCount,
        long unknownDeviceConditionItemCount
) {
}
