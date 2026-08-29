package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Minimal store-only payload allowed to leave the backend boundary. */
public record WeeklyReviewAiInput(
        int contractVersion,
        String promptVersion,
        int contentSchemaVersion,
        SummarySource summary,
        List<FactorSource> factors,
        List<ActionSource> actions,
        List<EvidenceSource> evidence
) {

    public WeeklyReviewAiInput {
        require(contractVersion == WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                "contractVersion must be 1");
        require(WeeklyReviewAiContract.PROMPT_VERSION.equals(promptVersion),
                "promptVersion must be weekly-interpretation-v22");
        require(contentSchemaVersion == WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                "contentSchemaVersion must be 4");
        requireNonNull(summary, "summary");
        factors = limited(factors, "factors", 3);
        actions = limited(actions, "actions", 3);
        evidence = limited(evidence, "evidence", 64);
        requireUnique(factors, FactorSource::factorId, "factorId");
        requireUnique(actions, ActionSource::actionId, "actionId");
        requireUnique(evidence, EvidenceSource::evidenceRef, "evidenceRef");
    }

    public record SummarySource(
            String outcomeText,
            List<String> evidenceRefs,
            List<String> allowedNumericLiterals
    ) {

        public SummarySource {
            requireText(outcomeText, "outcomeText");
            evidenceRefs = references(evidenceRefs, "summary.evidenceRefs");
            allowedNumericLiterals = strings(
                    allowedNumericLiterals, "summary.allowedNumericLiterals"
            );
        }
    }

    public record FactorSource(
            String factorId,
            String title,
            String detail,
            String effect,
            boolean causalLanguageAllowed,
            List<String> evidenceRefs,
            List<String> allowedNumericLiterals
    ) {

        public FactorSource {
            requireText(factorId, "factorId");
            requireText(title, "factor.title");
            requireText(detail, "factor.detail");
            require("POSITIVE".equals(effect) || "NEGATIVE".equals(effect),
                    "factor.effect must be POSITIVE or NEGATIVE");
            evidenceRefs = references(evidenceRefs, "factor.evidenceRefs");
            allowedNumericLiterals = strings(
                    allowedNumericLiterals, "factor.allowedNumericLiterals"
            );
        }
    }

    public record ActionSource(
            String actionId,
            String title,
            String check,
            List<String> evidenceRefs,
            List<String> allowedNumericLiterals
    ) {

        public ActionSource {
            requireText(actionId, "actionId");
            requireText(title, "action.title");
            requireText(check, "action.check");
            evidenceRefs = references(evidenceRefs, "action.evidenceRefs");
            allowedNumericLiterals = strings(
                    allowedNumericLiterals, "action.allowedNumericLiterals"
            );
        }
    }

    public record EvidenceSource(
            String evidenceRef,
            String label,
            String unit,
            String currentValue,
            String previousValue
    ) {

        public EvidenceSource {
            requireText(evidenceRef, "evidenceRef");
            requireText(label, "evidence.label");
            requireText(unit, "evidence.unit");
            require(currentValue != null || previousValue != null,
                    "evidence requires a current or previous value");
        }
    }

    private static List<String> references(
            List<String> values,
            String fieldName
    ) {
        List<String> result = strings(values, fieldName);
        require(!result.isEmpty(), fieldName + " must not be empty");
        return result;
    }

    private static List<String> strings(List<String> values, String fieldName) {
        List<String> result = List.copyOf(requireNonNull(values, fieldName));
        Set<String> unique = new HashSet<>();
        result.forEach(value -> require(
                unique.add(requireText(value, fieldName)),
                fieldName + " must be unique"
        ));
        return result;
    }

    private static <T> List<T> limited(
            List<T> values,
            String fieldName,
            int maximum
    ) {
        List<T> result = List.copyOf(requireNonNull(values, fieldName));
        require(result.size() <= maximum,
                fieldName + " exceeds the provider limit");
        return result;
    }

    private static <T> void requireUnique(
            List<T> values,
            Function<T, String> identifier,
            String fieldName
    ) {
        Set<String> unique = new HashSet<>();
        values.forEach(value -> require(
                unique.add(identifier.apply(value)),
                fieldName + " must be unique"
        ));
    }
}
