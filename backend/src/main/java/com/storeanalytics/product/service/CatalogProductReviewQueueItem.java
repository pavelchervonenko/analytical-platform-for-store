package com.storeanalytics.product.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CatalogProductReviewQueueItem(
        UUID productId,
        long productVersion,
        String externalId,
        String code,
        String name,
        String sourceKind,
        String sourceGroupPath,
        Instant firstSaleAt,
        LocalDate firstSaleDate,
        long saleItemCount,
        boolean hasUnmappedSales,
        String assignedAnalyticsCategoryCode,
        String assignedConditionType,
        String assignedPayrollCategoryCode,
        String suggestedAnalyticsCategoryCode
) { }
