package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Bounded store-only atoms and choices allowed to leave the backend boundary. */
public record WeeklyReviewAiInput(
        int contractVersion,
        String promptVersion,
        int contentSchemaVersion,
        String reportState,
        SummarySource summary,
        List<FactorSource> factors,
        List<ActionSource> actions,
        List<EvidenceSource> evidence
) {

    public WeeklyReviewAiInput {
        require(contractVersion == WeeklyReviewAiContract.INPUT_SCHEMA_VERSION,
                "contractVersion must be 4");
        require(WeeklyReviewAiContract.PROMPT_VERSION.equals(promptVersion),
                "promptVersion must be weekly-interpretation-v25");
        require(contentSchemaVersion == WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                "contentSchemaVersion must be 4");
        require("READY".equals(reportState) || "PARTIAL".equals(reportState),
                "reportState must be READY or PARTIAL");
        requireNonNull(summary, "summary");
        factors = limited(factors, "factors", 3);
        actions = limited(actions, "actions", 3);
        evidence = limited(evidence, "evidence", 64);
        requireUnique(factors, FactorSource::factorId, "factorId");
        requireUnique(actions, ActionSource::actionId, "actionId");
        requireUnique(evidence, EvidenceSource::evidenceRef, "evidenceRef");
        Set<String> factorIds = new HashSet<>();
        factors.forEach(value -> factorIds.add(value.factorId()));
        require(factorIds.containsAll(summary.allowedFocusFactorIds()),
                "summary focus factors must exist in factors");
    }

    public record SummarySource(
            String outcomeEffect,
            List<String> allowedSelectors,
            List<String> allowedFocusFactorIds,
            List<String> evidenceRefs
    ) {

        public SummarySource {
            requireEffect(outcomeEffect, true);
            allowedSelectors = nonEmptyStrings(
                    limited(allowedSelectors, "summary.allowedSelectors", 4),
                    "summary.allowedSelectors"
            );
            allowedFocusFactorIds = strings(
                    limited(
                            allowedFocusFactorIds,
                            "summary.allowedFocusFactorIds",
                            3
                    ),
                    "summary.allowedFocusFactorIds"
            );
            evidenceRefs = references(evidenceRefs, "summary.evidenceRefs");
        }
    }

    public record FactorSource(
            String factorId,
            String kind,
            String title,
            String direction,
            String effect,
            boolean causalLanguageAllowed,
            List<String> allowedSelectors,
            List<String> evidenceRefs
    ) {

        public FactorSource {
            requireText(factorId, "factorId");
            requireText(kind, "factor.kind");
            requireText(title, "factor.title");
            require("UP".equals(direction) || "DOWN".equals(direction)
                            || "FLAT".equals(direction)
                            || "UNKNOWN".equals(direction),
                    "factor.direction is invalid");
            requireEffect(effect, false);
            allowedSelectors = nonEmptyStrings(
                    limited(allowedSelectors, "factor.allowedSelectors", 2),
                    "factor.allowedSelectors"
            );
            evidenceRefs = references(evidenceRefs, "factor.evidenceRefs");
        }
    }

    public record ActionSource(
            String actionId,
            String title,
            String check,
            List<String> evidenceRefs
    ) {

        public ActionSource {
            requireText(actionId, "actionId");
            requireText(title, "action.title");
            requireText(check, "action.check");
            evidenceRefs = references(evidenceRefs, "action.evidenceRefs");
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

    private static void requireEffect(String effect, boolean summary) {
        boolean common = "POSITIVE".equals(effect)
                || "NEGATIVE".equals(effect);
        require(common || summary && ("NEUTRAL".equals(effect)
                        || "MIXED".equals(effect)),
                "effect is invalid");
    }

    private static List<String> references(
            List<String> values,
            String fieldName
    ) {
        List<String> result = nonEmptyStrings(values, fieldName);
        require(result.size() <= 10, fieldName + " exceeds ten items");
        return result;
    }

    private static List<String> nonEmptyStrings(
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
