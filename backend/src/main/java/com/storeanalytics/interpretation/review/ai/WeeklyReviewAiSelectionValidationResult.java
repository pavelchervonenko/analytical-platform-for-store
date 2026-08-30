package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import java.util.List;

record WeeklyReviewAiSelectionValidationResult(
        LlmValidationOutcome outcome,
        WeeklyReviewAiSelection selection,
        List<LlmValidationViolation> violations
) {

    WeeklyReviewAiSelectionValidationResult {
        requireNonNull(outcome, "outcome");
        violations = List.copyOf(requireNonNull(violations, "violations"));
        boolean valid = outcome == LlmValidationOutcome.VALID;
        require(valid == (selection != null),
                "only valid result may contain a selection");
        require(valid == violations.isEmpty(),
                "valid result must have no violations");
    }

    static WeeklyReviewAiSelectionValidationResult valid(
            WeeklyReviewAiSelection selection
    ) {
        return new WeeklyReviewAiSelectionValidationResult(
                LlmValidationOutcome.VALID,
                requireNonNull(selection, "selection"),
                List.of()
        );
    }

    static WeeklyReviewAiSelectionValidationResult invalid(
            LlmValidationOutcome outcome,
            List<LlmValidationViolation> violations
    ) {
        require(outcome != LlmValidationOutcome.VALID,
                "invalid result requires an invalid outcome");
        require(!requireNonNull(violations, "violations").isEmpty(),
                "invalid result requires violations");
        return new WeeklyReviewAiSelectionValidationResult(
                outcome, null, violations
        );
    }
}
