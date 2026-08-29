package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiEvaluationCorpus.EvaluationCase;
import java.util.List;

/** Runs the exact production semantic validator over the network-free corpus. */
final class WeeklyReviewAiOfflineEvaluator {

    private final WeeklyReviewAiSemanticValidator validator;

    WeeklyReviewAiOfflineEvaluator() {
        validator = new WeeklyReviewAiSemanticValidator(
                new WeeklyReviewAiStructuralValidator()
        );
    }

    List<EvaluationResult> evaluate() {
        return WeeklyReviewAiEvaluationCorpus.cases().stream()
                .map(this::evaluate)
                .toList();
    }

    private EvaluationResult evaluate(EvaluationCase evaluationCase) {
        WeeklyReviewAiValidationResult validation = validator.validate(
                evaluationCase.input(), evaluationCase.responseBody()
        );
        boolean outcomeMatches = validation.outcome()
                == evaluationCase.expectedOutcome();
        boolean violationMatches = evaluationCase.requiredViolationCode() == null
                ? validation.violations().isEmpty()
                : validation.violations().stream().anyMatch(value ->
                        evaluationCase.requiredViolationCode().equals(value.code())
                );
        return new EvaluationResult(
                evaluationCase.id(),
                outcomeMatches && violationMatches,
                validation
        );
    }

    record EvaluationResult(
            String caseId,
            boolean passed,
            WeeklyReviewAiValidationResult validation
    ) {
    }
}
