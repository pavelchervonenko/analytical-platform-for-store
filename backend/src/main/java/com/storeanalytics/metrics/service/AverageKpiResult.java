package com.storeanalytics.metrics.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AverageKpiResult(
        UUID storeId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate periodEnd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate previousPeriodStart,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate previousPeriodEnd,
        String formulaVersion,
        AverageMetricComparison averageReceipt,
        AverageMetricComparison additionalRevenuePerPhone,
        List<CategoryAverageEntry> categoryAveragePrices
) {
}
