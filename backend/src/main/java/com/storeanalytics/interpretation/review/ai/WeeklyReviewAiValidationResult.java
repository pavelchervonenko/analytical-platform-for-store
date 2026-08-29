package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import java.util.List;

public record WeeklyReviewAiValidationResult(
        LlmValidationOutcome outcome,
        WeeklyReviewAiContent content,
        String canonicalContent,
        List<LlmValidationViolation> violations,
        boolean semanticValidated
) {

    public WeeklyReviewAiValidationResult {
        requireNonNull(outcome, "outcome");
        violations = List.copyOf(requireNonNull(violations, "violations"));
        boolean valid = outcome == LlmValidationOutcome.VALID;
        require(valid == (content != null), "only valid result may contain content");
        require(valid == (canonicalContent != null),
                "only valid result may contain canonical content");
        require(valid == violations.isEmpty(),
                "valid result must have no violations");
        require(!semanticValidated || valid,
                "only valid result may be semantically validated");
    }

    public static WeeklyReviewAiValidationResult structurallyValid(
            WeeklyReviewAiContent content,
            String canonicalContent
    ) {
        return valid(content, canonicalContent, false);
    }

    public static WeeklyReviewAiValidationResult semanticallyValid(
            WeeklyReviewAiContent content,
            String canonicalContent
    ) {
        return valid(content, canonicalContent, true);
    }

    public WeeklyReviewAiValidationResult markSemanticallyValidated() {
        require(outcome == LlmValidationOutcome.VALID,
                "only valid result may be semantically validated");
        return semanticallyValid(content, canonicalContent);
    }

    private static WeeklyReviewAiValidationResult valid(
            WeeklyReviewAiContent content,
            String canonicalContent,
            boolean semanticValidated
    ) {
        return new WeeklyReviewAiValidationResult(
                LlmValidationOutcome.VALID,
                requireNonNull(content, "content"),
                requireNonNull(canonicalContent, "canonicalContent"),
                List.of(),
                semanticValidated
        );
    }

    public static WeeklyReviewAiValidationResult invalid(
            LlmValidationOutcome outcome,
            List<LlmValidationViolation> violations
    ) {
        require(outcome != LlmValidationOutcome.VALID,
                "invalid result requires an invalid outcome");
        require(!requireNonNull(violations, "violations").isEmpty(),
                "invalid result requires violations");
        return new WeeklyReviewAiValidationResult(
                outcome, null, null, violations, false
        );
    }
}
