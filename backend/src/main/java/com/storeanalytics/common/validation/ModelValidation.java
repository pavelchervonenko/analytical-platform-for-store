package com.storeanalytics.common.validation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public final class ModelValidation {

    private static final ObjectMapper JSON_READER = new ObjectMapper();

    private ModelValidation() {
    }

    public static <T> T requireNonNull(T value, String fieldName) {
        return Objects.requireNonNull(value, fieldName + " must not be null");
    }

    public static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public static String requireJson(String value, String fieldName) {
        requireText(value, fieldName);
        try {
            JsonNode json = JSON_READER.readTree(value);
            if (json == null) {
                throw new IllegalArgumentException(fieldName + " must contain JSON");
            }
        } catch (JacksonException exception) {
            throw new IllegalArgumentException(fieldName + " must contain valid JSON", exception);
        }
        return value;
    }

    public static UUID requirePersistedId(UUID value, String fieldName) {
        return requireNonNull(value, fieldName + " must reference a persisted entity");
    }

    public static BigDecimal requireNumeric(
            BigDecimal value,
            String fieldName,
            int precision,
            int scale
    ) {
        requireNonNull(value, fieldName);
        BigDecimal normalized;
        try {
            normalized = value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    fieldName + " must have at most " + scale + " decimal places",
                    exception
            );
        }
        if (normalized.precision() > precision) {
            throw new IllegalArgumentException(
                    fieldName + " exceeds numeric(" + precision + ", " + scale + ")"
            );
        }
        return normalized;
    }

    public static BigDecimal requireNonNegative(
            BigDecimal value,
            String fieldName,
            int precision,
            int scale
    ) {
        BigDecimal normalized = requireNumeric(value, fieldName, precision, scale);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return normalized;
    }

    public static BigDecimal requirePositive(
            BigDecimal value,
            String fieldName,
            int precision,
            int scale
    ) {
        BigDecimal normalized = requireNumeric(value, fieldName, precision, scale);
        if (normalized.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return normalized;
    }

    public static BigDecimal requireNullableNonNegative(
            BigDecimal value,
            String fieldName,
            int precision,
            int scale
    ) {
        return value == null ? null : requireNonNegative(value, fieldName, precision, scale);
    }

    public static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
