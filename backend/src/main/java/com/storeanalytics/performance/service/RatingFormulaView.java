package com.storeanalytics.performance.service;

import java.math.BigDecimal;

public record RatingFormulaView(
        String version,
        BigDecimal contributionWeight,
        BigDecimal efficiencyWeight,
        BigDecimal structureWeight,
        BigDecimal attachWeight,
        BigDecimal accessoryStructureWeight,
        BigDecimal serviceStructureWeight,
        BigDecimal minimumAttachDenominator,
        BigDecimal scoreCap,
        BigDecimal minimumCoveragePercent
) {
}
