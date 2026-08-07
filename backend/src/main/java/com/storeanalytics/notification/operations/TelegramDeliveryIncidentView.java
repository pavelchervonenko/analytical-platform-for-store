package com.storeanalytics.notification.operations;

import java.time.Instant;
import java.util.UUID;

public record TelegramDeliveryIncidentView(
        UUID deliveryId,
        String deliveryKind,
        String eventType,
        String storeName,
        String recipientName,
        String status,
        int attemptCount,
        int maxAttempts,
        Instant expiresAt,
        Instant nextAttemptAt,
        Instant leaseUntil,
        String errorCode,
        String errorSummary,
        Instant createdAt,
        Instant updatedAt
) {
}
