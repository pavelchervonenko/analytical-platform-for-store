package com.storeanalytics.salary.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollUnmappedProductIssue(
        UUID productId,
        String productName,
        String analyticsCategoryCode,
        LocalDate firstSaleDate,
        LocalDate lastSaleDate,
        long saleItemCount,
        long returnItemCount,
        BigDecimal netQuantity,
        BigDecimal netRevenue
) {
}
