package com.storeanalytics.notification.linking;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

record TelegramSubscriptionRow(
        UUID id,
        UUID userId,
        long telegramChatId,
        String status,
        String deliveryTimezone,
        boolean quietHoursEnabled,
        LocalTime quietHoursStart,
        LocalTime quietHoursEnd,
        Instant pendingExpiresAt,
        Instant confirmedAt,
        Instant blockedAt,
        Instant createdAt,
        long version
) {
}
