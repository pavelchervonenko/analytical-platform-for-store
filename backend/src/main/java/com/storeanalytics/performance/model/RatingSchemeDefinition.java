package com.storeanalytics.performance.model;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNegative;
import static com.storeanalytics.common.validation.ModelValidation.requirePositive;
import static com.storeanalytics.common.validation.ModelValidation.require;

import java.math.BigDecimal;

public record RatingSchemeDefinition(
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

    public RatingSchemeDefinition {
        contributionWeight = percentage(contributionWeight, "contributionWeight");
        efficiencyWeight = percentage(efficiencyWeight, "efficiencyWeight");
        structureWeight = percentage(structureWeight, "structureWeight");
        attachWeight = percentage(attachWeight, "attachWeight");
        accessoryStructureWeight = percentage(
                accessoryStructureWeight, "accessoryStructureWeight"
        );
        serviceStructureWeight = percentage(serviceStructureWeight, "serviceStructureWeight");
        minimumAttachDenominator = requirePositive(
                minimumAttachDenominator, "minimumAttachDenominator", 19, 3
        );
        scoreCap = requirePositive(scoreCap, "scoreCap", 6, 2);
        minimumCoveragePercent = percentage(
                minimumCoveragePercent, "minimumCoveragePercent"
        );
        require(scoreCap.compareTo(BigDecimal.valueOf(100)) >= 0,
                "scoreCap must be at least 100");
        require(sum(contributionWeight, efficiencyWeight, structureWeight, attachWeight)
                        .compareTo(new BigDecimal("100.00")) == 0,
                "direction weights must total 100");
        require(accessoryStructureWeight.add(serviceStructureWeight)
                        .compareTo(new BigDecimal("100.00")) == 0,
                "structure weights must total 100");
    }

    private static BigDecimal percentage(BigDecimal value, String fieldName) {
        BigDecimal normalized = requireNonNegative(value, fieldName, 5, 2);
        require(normalized.compareTo(BigDecimal.valueOf(100)) <= 0,
                fieldName + " must not exceed 100");
        return normalized;
    }

    private static BigDecimal sum(BigDecimal... values) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total;
    }
}
