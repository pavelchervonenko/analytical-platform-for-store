package com.storeanalytics.integration.livesklad.webhook;

import java.time.Instant;

record LiveSkladWebhookReceipt(
        LiveSkladWebhookKind kind,
        String eventId,
        String actionId,
        String actionGroupId,
        String actionName,
        String payload,
        String payloadSha256,
        Instant receivedAt
) {
}
