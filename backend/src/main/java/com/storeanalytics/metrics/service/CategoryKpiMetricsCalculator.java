package com.storeanalytics.metrics.service;

import com.storeanalytics.metrics.repository.CategoryMetricValues;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

final class CategoryKpiMetricsCalculator {

    private static final int MONEY_SCALE = 2;
    private static final int QUANTITY_SCALE = 3;
    private static final int PERCENT_SCALE = 2;

    private CategoryKpiMetricsCalculator() {
    }

    static CategoryKpiMetrics calculate(
            List<? extends CategoryMetricValues> values
    ) {
        BigDecimal netRevenue = values.stream()
                .map(CategoryMetricValues::netRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netQuantity = values.stream()
                .map(CategoryMetricValues::netQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal costAmountValue = values.stream()
                .map(CategoryMetricValues::costAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long includedItemCount = values.stream()
                .mapToLong(CategoryMetricValues::includedItemCount)
                .sum();
        long missingCostItemCount = values.stream()
                .mapToLong(CategoryMetricValues::missingCostItemCount)
                .sum();
        long unexpectedZeroCostItemCount = values.stream()
                .mapToLong(CategoryMetricValues::unexpectedZeroCostItemCount)
                .sum();
        boolean completeCostData = missingCostItemCount == 0;

        BigDecimal scaledRevenue = money(netRevenue);
        BigDecimal scaledQuantity = quantity(netQuantity);
        BigDecimal costAmount = completeCostData ? money(costAmountValue) : null;
        BigDecimal grossProfit = completeCostData
                ? money(scaledRevenue.subtract(costAmount))
                : null;
        BigDecimal averageGrossProfitPerUnit =
                grossProfit == null || scaledQuantity.signum() <= 0
                        ? null
                        : grossProfit.divide(scaledQuantity, MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal marginPercent = grossProfit == null || scaledRevenue.signum() == 0
                ? null
                : grossProfit.multiply(BigDecimal.valueOf(100))
                        .divide(scaledRevenue, PERCENT_SCALE, RoundingMode.HALF_UP);
        return new CategoryKpiMetrics(
                scaledRevenue,
                scaledQuantity,
                costAmount,
                grossProfit,
                averageGrossProfitPerUnit,
                marginPercent,
                new CategoryKpiDataQuality(
                        completeCostData,
                        includedItemCount,
                        missingCostItemCount,
                        unexpectedZeroCostItemCount
                )
        );
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal quantity(BigDecimal value) {
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }
}
