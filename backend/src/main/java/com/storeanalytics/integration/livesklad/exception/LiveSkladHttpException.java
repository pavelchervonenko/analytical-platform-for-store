package com.storeanalytics.integration.livesklad.exception;

import static com.storeanalytics.common.validation.ModelValidation.requireText;

public final class LiveSkladHttpException extends LiveSkladException {

    private final String operation;
    private final int statusCode;

    public LiveSkladHttpException(String operation, int statusCode) {
        super(requireText(operation, "operation") + ": HTTP " + statusCode);
        if (statusCode < 400 || statusCode > 599) {
            throw new IllegalArgumentException(
                    "LiveSklad HTTP status must be between 400 and 599"
            );
        }
        this.operation = operation;
        this.statusCode = statusCode;
    }

    public String getOperation() {
        return operation;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public LiveSkladFailureCategory getCategory() {
        return switch (statusCode) {
            case 401 -> LiveSkladFailureCategory.AUTHENTICATION;
            case 403 -> LiveSkladFailureCategory.AUTHORIZATION;
            case 404 -> LiveSkladFailureCategory.NOT_FOUND;
            case 409 -> LiveSkladFailureCategory.CONFLICT;
            case 429 -> LiveSkladFailureCategory.RATE_LIMIT;
            default -> statusCode >= 500
                    ? LiveSkladFailureCategory.UPSTREAM_SERVER
                    : LiveSkladFailureCategory.CLIENT_REQUEST;
        };
    }

    public boolean isRetryable() {
        return statusCode == 408
                || statusCode == 425
                || statusCode == 429
                || statusCode >= 500
                && statusCode != 501
                && statusCode != 505;
    }
}
