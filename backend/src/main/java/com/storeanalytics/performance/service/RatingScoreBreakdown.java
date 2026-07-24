package com.storeanalytics.performance.service;

import java.math.BigDecimal;

public record RatingScoreBreakdown(
        BigDecimal contributionScore,
        BigDecimal contributionWeightedPoints,
        BigDecimal efficiencyScore,
        BigDecimal efficiencyWeightedPoints,
        BigDecimal structureScore,
        BigDecimal structureWeightedPoints,
        BigDecimal attachScore,
        BigDecimal attachWeightedPoints,
        BigDecimal coveragePercent,
        BigDecimal overallScore
) {
}
