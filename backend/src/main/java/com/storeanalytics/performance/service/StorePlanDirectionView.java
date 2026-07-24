package com.storeanalytics.performance.service;

import java.math.BigDecimal;

public record StorePlanDirectionView(
        StorePlanDirectionCode code,
        StorePlanCriterionType criterionType,
        BigDecimal actualAmount,
        BigDecimal targetAmount,
        BigDecimal amountCompletionPercent,
        BigDecimal currentDailyPace,
        BigDecimal expectedAmountToDate,
        BigDecimal paceGapAmount,
        BigDecimal projectedAmount,
        BigDecimal projectedAmountCompletionPercent,
        BigDecimal remainingAmount,
        BigDecimal requiredPerRemainingDay,
        BigDecimal actualSharePercent,
        BigDecimal targetSharePercent,
        BigDecimal shareGapPercentagePoints,
        BigDecimal criterionCompletionPercent,
        boolean achieved,
        StorePlanProgressStatus status
) {
}
