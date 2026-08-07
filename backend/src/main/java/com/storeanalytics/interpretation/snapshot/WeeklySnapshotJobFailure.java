package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.requireText;

public record WeeklySnapshotJobFailure(
        boolean retryable,
        String errorCode,
        String safeSummary
) {

    public WeeklySnapshotJobFailure {
        errorCode = requireText(errorCode, "errorCode");
        safeSummary = requireText(safeSummary, "safeSummary");
    }
}
