package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

public record LlmAnalysisEnqueueResult(LlmAnalysisJob job, boolean created) {

    public LlmAnalysisEnqueueResult {
        requireNonNull(job, "job");
    }
}
