package com.storeanalytics.integration.livesklad.exception;

import static com.storeanalytics.common.validation.ModelValidation.requireText;

public final class LiveSkladTransportException extends LiveSkladException {

    private final String operation;

    public LiveSkladTransportException(
            String operation,
            Throwable cause
    ) {
        super(requireText(operation, "operation") + ": transport failure", cause);
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }

    public LiveSkladFailureCategory getCategory() {
        return LiveSkladFailureCategory.TRANSPORT;
    }
}
