package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Provider-owned editorial choices; it intentionally contains no free text. */
public record WeeklyReviewAiSelection(
        int selectionSchemaVersion,
        SummarySelection summary,
        List<FactorSelection> factorSelections
) {

    public WeeklyReviewAiSelection {
        require(selectionSchemaVersion
                        == WeeklyReviewAiContract.SELECTION_SCHEMA_VERSION,
                "selectionSchemaVersion must be 1");
        requireNonNull(summary, "summary");
        factorSelections = List.copyOf(requireNonNull(
                factorSelections, "factorSelections"
        ));
        require(factorSelections.size() <= 3,
                "factorSelections must contain at most three items");
        requireUnique(
                factorSelections,
                FactorSelection::factorId,
                "factorSelections.factorId"
        );
    }

    public record SummarySelection(
            String selector,
            String primaryFactorId,
            String secondaryFactorId
    ) {

        public SummarySelection {
            requireText(selector, "summary.selector");
            optionalObjectId(primaryFactorId, "summary.primaryFactorId");
            optionalObjectId(secondaryFactorId, "summary.secondaryFactorId");
            require(primaryFactorId == null
                            || !primaryFactorId.equals(secondaryFactorId),
                    "summary factor ids must be distinct");
        }
    }

    public record FactorSelection(String factorId, String selector) {

        public FactorSelection {
            requireText(factorId, "factorId");
            requireText(selector, "factor.selector");
        }
    }

    private static void optionalObjectId(String value, String fieldName) {
        if (value != null) {
            requireText(value, fieldName);
        }
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
