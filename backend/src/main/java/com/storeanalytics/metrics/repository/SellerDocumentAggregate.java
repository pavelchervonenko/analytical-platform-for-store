package com.storeanalytics.metrics.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** Gross sales/returns amounts, all SALE counts and the narrower completed-sale sample. */
public record SellerDocumentAggregate(
        UUID employeeId,
        BigDecimal salesRevenue,
        BigDecimal returnRevenue,
        long saleDocumentCount,
        long returnDocumentCount,
        long completedSaleCount
) {

    public BigDecimal netRevenue() {
        return salesRevenue.subtract(returnRevenue);
    }
}
