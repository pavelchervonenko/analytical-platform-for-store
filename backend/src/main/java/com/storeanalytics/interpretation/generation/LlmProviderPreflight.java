package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.math.BigDecimal;

public record LlmProviderPreflight(
        int estimatedInputTokens,
        int contextWindowTokens,
        BigDecimal estimatedMaximumCost,
        String costCurrency
) {

    public LlmProviderPreflight {
        require(estimatedInputTokens > 0, "estimatedInputTokens must be positive");
        require(contextWindowTokens > 0, "contextWindowTokens must be positive");
        requireNonNull(estimatedMaximumCost, "estimatedMaximumCost");
        require(estimatedMaximumCost.compareTo(BigDecimal.ZERO) >= 0,
                "estimatedMaximumCost must not be negative");
        require(costCurrency != null && costCurrency.matches("[A-Z]{3}"),
                "costCurrency must be a three-letter uppercase code");
    }
}
