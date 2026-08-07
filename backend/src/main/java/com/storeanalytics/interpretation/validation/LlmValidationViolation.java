package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.requireText;

public record LlmValidationViolation(
        String code,
        String path,
        String reference
) {

    public LlmValidationViolation {
        requireText(code, "code");
        requireText(path, "path");
    }
}
