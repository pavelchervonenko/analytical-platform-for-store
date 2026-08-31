package com.storeanalytics.metrics.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record OverviewMetricsResult(
        UUID storeId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodEnd,
        OverviewMetricScope scope,
        String formulaVersion,
        BigDecimal netRevenue,
        BigDecimal netQuantity,
        BigDecimal costAmount,
        BigDecimal grossProfit,
        BigDecimal marginPercent,
        OverviewCommercialMetric additional,
        OverviewCommercialMetric accessory,
        OverviewCommercialMetric service,
        List<CategoryKpiGroup> salesGroups,
        OverviewMetricsDataQuality dataQuality
) {
}
