package com.storeanalytics.performance.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record RatingSchemeView(
        UUID id,
        String code,
        LocalDate effectiveFrom,
        BigDecimal contributionWeight,
        BigDecimal efficiencyWeight,
        BigDecimal structureWeight,
        BigDecimal attachWeight,
        BigDecimal accessoryStructureWeight,
        BigDecimal serviceStructureWeight,
        BigDecimal minimumAttachDenominator,
        BigDecimal scoreCap,
        BigDecimal minimumCoveragePercent,
        UUID createdBy,
        Instant createdAt
) {
}
