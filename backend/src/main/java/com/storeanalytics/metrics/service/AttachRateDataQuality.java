package com.storeanalytics.metrics.service;

public record AttachRateDataQuality(
        long unmatchedNumeratorItemCount,
        long ambiguousWarrantyItemCount,
        long unknownDeviceConditionItemCount,
        long unassignedReturnItemCount
) {
    public AttachRateDataQuality(long unmatchedNumeratorItemCount, long ambiguousWarrantyItemCount,
                                 long unknownDeviceConditionItemCount) {
        this(unmatchedNumeratorItemCount, ambiguousWarrantyItemCount, unknownDeviceConditionItemCount, 0);
    }
}
