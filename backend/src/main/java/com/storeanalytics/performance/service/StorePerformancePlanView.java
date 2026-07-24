package com.storeanalytics.performance.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StorePerformancePlanView(
        UUID id,
        UUID storeId,
        LocalDate planMonth,
        BigDecimal revenueTarget,
        BigDecimal accessoryShareTarget,
        BigDecimal serviceShareTarget,
        BigDecimal additionalShareTarget,
        UUID updatedBy,
        long version,
        Instant updatedAt
) {
}
