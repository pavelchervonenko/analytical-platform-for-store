package com.storeanalytics.notification.delivery;

import java.time.Instant;

public final class TelegramSendException extends RuntimeException {

    private final TelegramSendFailureKind kind;
    private final Integer httpStatus;
    private final Instant retryAfterAt;

    public TelegramSendException(
            TelegramSendFailureKind kind,
            String safeMessage,
            Integer httpStatus,
            Instant retryAfterAt,
            Throwable cause
    ) {
        super(safeMessage, cause);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.httpStatus = httpStatus;
        this.retryAfterAt = retryAfterAt;
    }

    public TelegramSendFailureKind getKind() {
        return kind;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Instant getRetryAfterAt() {
        return retryAfterAt;
    }
}
