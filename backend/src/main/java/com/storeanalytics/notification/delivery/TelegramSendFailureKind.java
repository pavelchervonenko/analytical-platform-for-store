package com.storeanalytics.notification.delivery;

public enum TelegramSendFailureKind {
    RATE_LIMITED,
    TRANSIENT_PROVIDER,
    PERMANENT_PROVIDER_REJECTED,
    BOT_BLOCKED,
    AUTHENTICATION,
    INVALID_REQUEST,
    UNKNOWN_OUTCOME
}
