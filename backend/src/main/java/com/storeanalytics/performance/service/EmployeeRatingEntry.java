package com.storeanalytics.performance.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record EmployeeRatingEntry(
        UUID employeeId,
        String displayName,
        boolean employeeActive,
        boolean assignmentActive,
        boolean participatesInRanking,
        boolean ratingEligible,
        long shiftCount,
        BigDecimal workedHours,
        BigDecimal netRevenue,
        BigDecimal storeRevenueSharePercent,
        BigDecimal revenuePerShift,
        BigDecimal revenuePerHour,
        BigDecimal accessoryRevenue,
        BigDecimal accessorySharePercent,
        BigDecimal serviceRevenue,
        BigDecimal serviceSharePercent,
        BigDecimal additionalRevenue,
        BigDecimal additionalSharePercent,
        RatingScoreBreakdown scores,
        boolean ranked,
        Integer rank,
        List<EmployeeAttachRatingEntry> attachRates
) {

    public EmployeeRatingEntry withRank(Integer newRank) {
        return new EmployeeRatingEntry(
                employeeId,
                displayName,
                employeeActive,
                assignmentActive,
                participatesInRanking,
                ratingEligible,
                shiftCount,
                workedHours,
                netRevenue,
                storeRevenueSharePercent,
                revenuePerShift,
                revenuePerHour,
                accessoryRevenue,
                accessorySharePercent,
                serviceRevenue,
                serviceSharePercent,
                additionalRevenue,
                additionalSharePercent,
                scores,
                newRank != null,
                newRank,
                attachRates
        );
    }
}
