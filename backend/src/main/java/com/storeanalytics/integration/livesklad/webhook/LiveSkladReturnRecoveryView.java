package com.storeanalytics.integration.livesklad.webhook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LiveSkladReturnRecoveryView(
        UUID id,
        String externalId,
        String expectedDocumentNumber,
        BigDecimal expectedNetAmount,
        int expectedPositionCount,
        String status,
        int attemptCount,
        boolean terminalFailure,
        String errorCode,
        Instant requestedAt,
        Instant processedAt
) {
}
