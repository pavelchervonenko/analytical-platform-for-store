package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class VersionedWeeklyInterpretationResponseValidator {

    private final Map<Integer, WeeklyInterpretationResponseValidationStrategy> strategies;

    public VersionedWeeklyInterpretationResponseValidator(
            List<WeeklyInterpretationResponseValidationStrategy> strategies
    ) {
        Map<Integer, WeeklyInterpretationResponseValidationStrategy> indexed =
                new HashMap<>();
        for (WeeklyInterpretationResponseValidationStrategy strategy : strategies) {
            WeeklyInterpretationResponseValidationStrategy previous = indexed.put(
                    strategy.contentSchemaVersion(),
                    strategy
            );
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate LLM response validator for content schema version "
                                + strategy.contentSchemaVersion()
                );
            }
        }
        this.strategies = Map.copyOf(indexed);
    }

    public LlmResponseValidationResult validate(
            int contentSchemaVersion,
            WeeklyInterpretationInput input,
            String responseBody
    ) {
        WeeklyInterpretationResponseValidationStrategy strategy =
                strategies.get(contentSchemaVersion);
        if (strategy == null) {
            throw new IllegalArgumentException(
                    "Unsupported LLM content schema version: " + contentSchemaVersion
            );
        }
        return strategy.validate(
                requireNonNull(input, "input"),
                requireNonNull(responseBody, "responseBody")
        );
    }
}
