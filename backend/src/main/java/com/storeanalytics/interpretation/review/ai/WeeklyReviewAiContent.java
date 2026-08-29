package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Strict typed form of the provider-owned schema4 enrichment. */
public record WeeklyReviewAiContent(
        int schemaVersion,
        Summary summary,
        List<FactorExplanation> factorExplanations,
        List<ActionWording> actionWordings
) {

    private static final int MAX_ITEMS = 3;
    private static final Pattern OBJECT_ID = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9:._-]+$"
    );
    private static final Pattern EVIDENCE_REF = Pattern.compile(
            "^[A-Z][A-Z0-9:._-]+$"
    );

    public WeeklyReviewAiContent {
        require(schemaVersion == WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                "schemaVersion must be 4");
        requireNonNull(summary, "summary");
        factorExplanations = bounded(
                factorExplanations, "factorExplanations"
        );
        actionWordings = bounded(actionWordings, "actionWordings");
        requireUnique(
                factorExplanations,
                FactorExplanation::factorId,
                "factorExplanations.factorId"
        );
        requireUnique(
                actionWordings,
                ActionWording::actionId,
                "actionWordings.actionId"
        );
    }

    public record Summary(String text, List<String> evidenceRefs) {

        public Summary {
            text = boundedText(text, "summary.text", 600);
            evidenceRefs = evidence(evidenceRefs, "summary.evidenceRefs");
        }
    }

    public record FactorExplanation(
            String factorId,
            String text,
            List<String> evidenceRefs
    ) {

        public FactorExplanation {
            factorId = objectId(factorId, "factorId");
            text = boundedText(text, "factorExplanation.text", 400);
            evidenceRefs = evidence(
                    evidenceRefs, "factorExplanation.evidenceRefs"
            );
        }
    }

    public record ActionWording(String actionId, String title, String check) {

        public ActionWording {
            actionId = objectId(actionId, "actionId");
            title = boundedText(title, "actionWording.title", 240);
            check = boundedText(check, "actionWording.check", 320);
        }
    }

    private static <T> List<T> bounded(List<T> values, String fieldName) {
        List<T> result = List.copyOf(requireNonNull(values, fieldName));
        require(result.size() <= MAX_ITEMS,
                fieldName + " must contain at most three items");
        return result;
    }

    private static List<String> evidence(
            List<String> values,
            String fieldName
    ) {
        List<String> result = List.copyOf(requireNonNull(values, fieldName));
        require(!result.isEmpty(), fieldName + " must not be empty");
        require(result.size() <= 10,
                fieldName + " must contain at most ten items");
        Set<String> unique = new HashSet<>();
        for (String value : result) {
            String reference = requireText(value, fieldName);
            require(reference.length() <= 160 && EVIDENCE_REF.matcher(reference).matches(),
                    fieldName + " contains an invalid reference");
            require(unique.add(reference), fieldName + " must be unique");
        }
        return result;
    }

    private static String objectId(String value, String fieldName) {
        String result = requireText(value, fieldName);
        require(result.length() >= 3 && result.length() <= 160
                        && OBJECT_ID.matcher(result).matches(),
                fieldName + " is invalid");
        return result;
    }

    private static String boundedText(
            String value,
            String fieldName,
            int maximumLength
    ) {
        String result = requireText(value, fieldName);
        require(result.length() <= maximumLength,
                fieldName + " is too long");
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
