package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;

public record LlmAnalysisPlanningResult(
        int candidatesScanned,
        int jobsCreated,
        int alreadyPlanned
) {

    public LlmAnalysisPlanningResult {
        require(candidatesScanned >= 0, "candidatesScanned must not be negative");
        require(jobsCreated >= 0, "jobsCreated must not be negative");
        require(alreadyPlanned >= 0, "alreadyPlanned must not be negative");
        require(jobsCreated + alreadyPlanned == candidatesScanned,
                "planning counters must reconcile");
    }
}
