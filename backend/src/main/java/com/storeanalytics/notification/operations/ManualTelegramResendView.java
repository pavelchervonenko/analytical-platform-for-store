package com.storeanalytics.notification.operations;

import java.time.Instant;
import java.util.UUID;

public record ManualTelegramResendView(
        UUID deliveryId,
        UUID sourceDeliveryId,
        String status,
        Instant scheduledAt,
        Instant expiresAt
) {
}
