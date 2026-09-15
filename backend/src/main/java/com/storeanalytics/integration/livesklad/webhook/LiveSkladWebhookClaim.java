package com.storeanalytics.integration.livesklad.webhook;

import java.math.BigDecimal;
import java.util.List;
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
        Integer recoveryExpectedPositionCount,
        LiveSkladReturnRecoveryMode recoveryMode,
        String recoveryExpectedCurrentEmployeeExternalId,
        String recoveryExpectedOriginalSaleExternalId,
        String recoveryExpectedOriginalEmployeeExternalId,
        List<RecoverLiveSkladReturnLinkExpectation> recoveryExpectedOriginalLinks
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
                null,
                null,
                null,
                null,
                null,
                List.of()
        );
    }

    boolean recovery() {
        return recoveryExpectedDocumentNumber != null;
    }

    boolean existingOrphanRelink() {
        return recovery()
                && recoveryMode
                == LiveSkladReturnRecoveryMode.EXISTING_ORPHAN_RELINK;
    }
}
