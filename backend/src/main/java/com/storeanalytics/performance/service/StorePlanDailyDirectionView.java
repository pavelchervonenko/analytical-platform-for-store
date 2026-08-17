package com.storeanalytics.performance.service;

import java.math.BigDecimal;

public record StorePlanDailyDirectionView(
        BigDecimal actualAmount,
        BigDecimal actualSharePercent,
        BigDecimal targetAmount,
        BigDecimal targetSharePercent,
        BigDecimal cumulativeGapAmount
) {
}
