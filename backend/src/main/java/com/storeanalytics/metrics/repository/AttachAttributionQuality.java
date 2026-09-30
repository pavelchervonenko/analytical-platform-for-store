package com.storeanalytics.metrics.repository;

/** Store-wide potential attribution risk, deliberately without store quantities or money. */
public record AttachAttributionQuality(
        String metricCode,
        long pendingWarrantyItemCount,
        long unassignedReturnItemCount,
        long unassignedMetricReturnItemCount,
        boolean pendingCatalogRole
) {
    public AttachAttributionQuality(String metricCode, long pendingWarrantyItemCount,
            long unassignedReturnItemCount, long unassignedMetricReturnItemCount) {
        this(metricCode, pendingWarrantyItemCount, unassignedReturnItemCount, unassignedMetricReturnItemCount, false);
    }

    public boolean preliminary() {
        return (metricCode.startsWith("WARRANTY_GENERIC_") && pendingWarrantyItemCount > 0)
                || unassignedMetricReturnItemCount > 0 || pendingCatalogRole;
    }
}
