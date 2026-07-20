package com.storeanalytics.integration.livesklad.exception;

import java.time.Duration;

public class LiveSkladRateLimitException extends LiveSkladException {

    private final Duration retryAfter;

    public LiveSkladRateLimitException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
