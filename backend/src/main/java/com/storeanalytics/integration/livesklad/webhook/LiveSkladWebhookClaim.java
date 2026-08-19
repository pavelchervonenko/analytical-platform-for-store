package com.storeanalytics.integration.livesklad.webhook;

import java.math.BigDecimal;
import java.util.UUID;

record LiveSkladWebhookClaim(
        UUID id,
        String eventId,
        String payload,
        boolean payloadMismatch,
        int attemptCount,
        String sourceDocumentId,
        String recoveryExpectedDocumentNumber,
        BigDecimal recoveryExpectedNetAmount,
        Integer recoveryExpectedPositionCount
) {
    LiveSkladWebhookClaim(
            UUID id,
            String eventId,
            String payload,
            boolean payloadMismatch,
            int attemptCount
    ) {
        this(
                id,
                eventId,
                payload,
                payloadMismatch,
                attemptCount,
                null,
                null,
                null,
                null
        );
    }


    boolean recovery() {
        return recoveryExpectedDocumentNumber != null;
    }
}
