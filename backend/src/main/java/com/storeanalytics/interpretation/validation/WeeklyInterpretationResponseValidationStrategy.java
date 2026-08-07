package com.storeanalytics.interpretation.validation;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;

public interface WeeklyInterpretationResponseValidationStrategy {

    int contentSchemaVersion();

    LlmResponseValidationResult validate(
            WeeklyInterpretationInput input,
            String responseBody
    );
}
