package com.storeanalytics.notification.delivery;

import java.time.Instant;
import java.util.UUID;

public record PreparedTelegramDelivery(
        UUID deliveryId,
        UUID attemptId,
        UUID subscriptionId,
        int attemptNumber,
        int maxAttempts,
        long chatId,
        String text,
        String contentHash,
        Instant expiresAt,
        String leaseOwner
) {
}
