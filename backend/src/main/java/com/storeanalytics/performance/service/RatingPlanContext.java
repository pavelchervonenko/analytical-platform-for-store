package com.storeanalytics.performance.service;

import java.math.BigDecimal;

public record RatingPlanContext(
        boolean complete,
        BigDecimal coveragePercent,
        BigDecimal proratedRevenueTarget,
        BigDecimal accessoryShareTarget,
        BigDecimal serviceShareTarget,
        BigDecimal additionalShareTarget,
        BigDecimal actualStoreRevenue,
        BigDecimal revenueAchievementPercent
) {
}
