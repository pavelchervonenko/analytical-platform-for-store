package com.storeanalytics.sync.model;

import static com.storeanalytics.common.validation.ModelValidation.requireText;

public record SyncErrorDetails(
        String stage,
        String entityType,
        String externalId,
        String errorCode,
        String errorMessage,
        boolean retryable
) {

    public SyncErrorDetails {
        stage = requireText(stage, "stage");
        errorMessage = requireText(errorMessage, "errorMessage");
    }
}
