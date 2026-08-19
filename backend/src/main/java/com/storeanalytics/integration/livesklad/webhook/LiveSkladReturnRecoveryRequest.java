package com.storeanalytics.integration.livesklad.webhook;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

record LiveSkladReturnRecoveryRequest(
        UUID id,
        UUID requestedBy,
        String idempotencyKey,
        String externalId,
        String documentNumber,
        BigDecimal netAmount,
        int positionCount,
        String reason,
        String eventId,
        String payload,
        String payloadSha256,
        Instant requestedAt
) {
}
