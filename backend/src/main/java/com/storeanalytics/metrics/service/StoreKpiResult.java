package com.storeanalytics.metrics.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record StoreKpiResult(
        UUID storeId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodEnd,
        String formulaVersion,
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        BigDecimal grossProfit,
        BigDecimal marginPercent,
        StoreKpiDataQuality dataQuality
) {
}
