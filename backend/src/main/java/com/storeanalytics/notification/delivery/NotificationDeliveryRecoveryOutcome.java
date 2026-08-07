package com.storeanalytics.notification.delivery;

public enum NotificationDeliveryRecoveryOutcome {
    NONE,
    RETRY_RELEASED,
    CANCELLED,
    EXPIRED,
    UNKNOWN_OUTCOME
}
