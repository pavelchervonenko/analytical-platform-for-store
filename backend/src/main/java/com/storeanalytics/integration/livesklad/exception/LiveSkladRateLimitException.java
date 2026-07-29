package com.storeanalytics.integration.livesklad.exception;

import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.time.Duration;

public class LiveSkladRateLimitException extends LiveSkladException {

    private static final int STATUS_CODE = 429;

    private final String operation;
    private final Duration retryAfter;

    public LiveSkladRateLimitException(String operation, Duration retryAfter) {
        super(requireText(operation, "operation") + ": HTTP 429 rate limited");
        this.operation = operation;
        this.retryAfter = retryAfter;
    }

    public String getOperation() {
        return operation;
    }

    public int getStatusCode() {
        return STATUS_CODE;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public LiveSkladFailureCategory getCategory() {
        return LiveSkladFailureCategory.RATE_LIMIT;
    }
}
