package com.storeanalytics.interpretation.validation;

public record StructuralValidationViolation(
        String keyword,
        String path
) {
}
