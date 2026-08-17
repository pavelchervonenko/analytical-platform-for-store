package com.storeanalytics.performance.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StorePlanDailyActual(
        LocalDate businessDate,
        BigDecimal revenueAmount,
        BigDecimal accessoryAmount,
        BigDecimal serviceAmount
) {
}
