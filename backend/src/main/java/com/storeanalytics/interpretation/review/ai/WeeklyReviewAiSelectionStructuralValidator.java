package com.storeanalytics.interpretation.review.ai;

import com.storeanalytics.interpretation.validation.LlmJsonSchemaValidator;
import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import com.storeanalytics.interpretation.validation.StructuralValidationViolation;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strictly parses selector-only provider output without repair or defaults. */
@Component
public final class WeeklyReviewAiSelectionStructuralValidator {

    private static final int MAX_VIOLATIONS = 100;

    private final ObjectMapper objectMapper;
    private final LlmJsonSchemaValidator schemaValidator;

    public WeeklyReviewAiSelectionStructuralValidator() {
        objectMapper = JsonMapper.builder()
                .findAndAddModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build();
        schemaValidator = new LlmJsonSchemaValidator(
                WeeklyReviewAiContract.SELECTION_SCHEMA
        );
    }

    public WeeklyReviewAiSelectionValidationResult validate(
            String responseBody
    ) {
        if (responseBody == null || responseBody.isBlank()) {
            return invalid("INVALID_JSON", "$", null);
        }
        List<StructuralValidationViolation> structural;
        try {
            structural = schemaValidator.validate(responseBody);
        } catch (RuntimeException exception) {
            return invalid("INVALID_JSON", "$", null);
        }
        if (!structural.isEmpty()) {
            return WeeklyReviewAiSelectionValidationResult.invalid(
                    LlmValidationOutcome.STRUCTURAL_INVALID,
                    structural.stream()
                            .limit(MAX_VIOLATIONS)
                            .map(this::violation)
                            .toList()
            );
        }
        try {
            return WeeklyReviewAiSelectionValidationResult.valid(
                    objectMapper.readValue(
                            responseBody,
                            WeeklyReviewAiSelection.class
                    )
            );
        } catch (RuntimeException exception) {
            return invalid("TYPED_CONTRACT", "$", null);
        }
    }

    private LlmValidationViolation violation(
            StructuralValidationViolation value
    ) {
        String path = value.path() == null || value.path().isBlank()
                ? "$"
                : value.path();
        return new LlmValidationViolation(
                "SCHEMA_" + value.keyword().toUpperCase(),
                path,
                null
        );
    }

    private WeeklyReviewAiSelectionValidationResult invalid(
            String code,
            String path,
            String reference
    ) {
        return WeeklyReviewAiSelectionValidationResult.invalid(
                LlmValidationOutcome.STRUCTURAL_INVALID,
                List.of(new LlmValidationViolation(code, path, reference))
        );
    }
}
