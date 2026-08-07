package com.storeanalytics.interpretation.publication;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import java.util.UUID;

public record LlmPublicationResult(
        LlmAnalysisJob job,
        UUID interpretationId,
        UUID notificationEventId,
        int interpretationRevision
) {

    public LlmPublicationResult {
        requireNonNull(job, "job");
        requireNonNull(interpretationId, "interpretationId");
        requireNonNull(notificationEventId, "notificationEventId");
        require(interpretationRevision > 0,
                "interpretationRevision must be positive");
    }
}
