package com.storeanalytics.notification.fanout;

import java.time.Instant;
import java.util.UUID;

public record DailyNotificationEvent(
        UUID id,
        UUID storeId,
        String storeName,
        String eventType,
        String eventPayload,
        String payloadHash,
        Instant notBefore,
        Instant expiresAt
) {
}
