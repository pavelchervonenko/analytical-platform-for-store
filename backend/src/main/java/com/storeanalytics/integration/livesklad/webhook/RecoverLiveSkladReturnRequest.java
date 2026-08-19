package com.storeanalytics.integration.livesklad.webhook;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record RecoverLiveSkladReturnRequest(
        @NotBlank @Size(max = 256) String externalId,
        @NotBlank @Size(max = 128) String expectedDocumentNumber,
        @NotNull @Positive BigDecimal expectedNetAmount,
        @Positive int expectedPositionCount,
        @NotBlank @Size(max = 500) String reason
) {
}
