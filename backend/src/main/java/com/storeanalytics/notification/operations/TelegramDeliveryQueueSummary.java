package com.storeanalytics.notification.operations;

import java.time.Instant;

public record TelegramDeliveryQueueSummary(
        TelegramDeliveryAttentionLevel attentionLevel,
        long readyPending,
        long readyRetries,
        long running,
        long overdueRunning,
        long permanentFailed,
        long unknownOutcome,
        long activeSubscriptions,
        long blockedSubscriptions,
        Instant oldestReadyAt
) {
}
