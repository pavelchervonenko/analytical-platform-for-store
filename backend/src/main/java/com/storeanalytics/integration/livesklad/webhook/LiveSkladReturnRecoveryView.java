package com.storeanalytics.integration.livesklad.webhook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LiveSkladReturnRecoveryView(
        UUID id,
        String externalId,
        String expectedDocumentNumber,
        BigDecimal expectedNetAmount,
        int expectedPositionCount,
        LiveSkladReturnRecoveryMode mode,
        String expectedCurrentEmployeeExternalId,
        String expectedOriginalSaleExternalId,
        String expectedOriginalEmployeeExternalId,
        List<RecoverLiveSkladReturnLinkExpectation> expectedOriginalLinks,
        String status,
        int attemptCount,
        boolean terminalFailure,
        String errorCode,
        Instant requestedAt,
        Instant processedAt
) {
}
