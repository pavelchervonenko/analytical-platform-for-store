package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollCategoryCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PayrollUnmappedProductView(
        UUID productId,
        String productName,
        String analyticsCategoryCode,
        LocalDate firstSaleDate,
        LocalDate lastSaleDate,
        long saleItemCount,
        long returnItemCount,
        BigDecimal netQuantity,
        BigDecimal netRevenue,
        PayrollCategoryCode suggestedCategoryCode,
        String suggestionReason
) {
}
