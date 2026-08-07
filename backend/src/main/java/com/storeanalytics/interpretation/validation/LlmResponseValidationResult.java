package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.List;

public record LlmResponseValidationResult(
        LlmValidationOutcome outcome,
        String canonicalContent,
        List<LlmValidationViolation> violations
) {

    public LlmResponseValidationResult {
        requireNonNull(outcome, "outcome");
        violations = List.copyOf(requireNonNull(violations, "violations"));
        require((outcome == LlmValidationOutcome.VALID) == violations.isEmpty(),
                "valid result must have no violations");
        require((outcome == LlmValidationOutcome.VALID) == (canonicalContent != null),
                "only valid result may contain canonical content");
    }

    public static LlmResponseValidationResult valid(String canonicalContent) {
        return new LlmResponseValidationResult(
                LlmValidationOutcome.VALID,
                requireNonNull(canonicalContent, "canonicalContent"),
                List.of()
        );
    }

    public static LlmResponseValidationResult invalid(
            LlmValidationOutcome outcome,
            List<LlmValidationViolation> violations
    ) {
        require(outcome != LlmValidationOutcome.VALID,
                "invalid result requires an invalid outcome");
        require(!requireNonNull(violations, "violations").isEmpty(),
                "invalid result requires violations");
        return new LlmResponseValidationResult(outcome, null, violations);
    }
}
