package com.storeanalytics.integration.livesklad.webhook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RecoverLiveSkladReturnLinkExpectation(
        @NotBlank @Size(max = 256) String returnPositionExternalId,
        @NotBlank @Size(max = 256) String originalSalePositionExternalId,
        @NotBlank @Size(max = 256) String productExternalId,
        @NotNull @Positive BigDecimal expectedQuantity,
        @NotNull @PositiveOrZero BigDecimal expectedNetAmount,
        @PositiveOrZero BigDecimal expectedCostAmount
) {
}
