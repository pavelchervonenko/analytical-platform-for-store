package com.storeanalytics.performance.service;

import java.math.BigDecimal;
import java.util.List;

public record EmployeeRatingDynamics(
        Integer previousRank,
        Integer currentRank,
        Integer rankImprovement,
        BigDecimal overallScoreChange,
        BigDecimal revenueChange,
        BigDecimal revenuePerHourChange,
        BigDecimal accessoryShareChange,
        BigDecimal serviceShareChange,
        BigDecimal additionalShareChange,
        List<EmployeeAttachRateChange> attachRateChanges
) {
}
