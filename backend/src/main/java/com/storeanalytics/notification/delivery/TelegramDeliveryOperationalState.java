package com.storeanalytics.notification.delivery;

public record TelegramDeliveryOperationalState(
        long readyPending,
        long readyRetry,
        long authenticationRetry,
        long running,
        long expiredLease,
        long permanentFailed,
        long unknownOutcome,
        long blockedSubscription
) {
}
