package com.storeanalytics.integration.livesklad.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record RecoverLiveSkladReturnRequest(
        @NotBlank @Size(max = 256) String externalId,
        @NotBlank @Size(max = 128) String expectedDocumentNumber,
        @NotNull @PositiveOrZero BigDecimal expectedNetAmount,
        @Positive int expectedPositionCount,
        LiveSkladReturnRecoveryMode mode,
        @Size(max = 256) String expectedCurrentEmployeeExternalId,
        @Size(max = 256) String expectedOriginalSaleExternalId,
        @Size(max = 256) String expectedOriginalEmployeeExternalId,
        @Size(max = 10_000)
        List<@Valid RecoverLiveSkladReturnLinkExpectation> expectedOriginalLinks,
        @NotBlank @Size(max = 500) String reason
) {
}
