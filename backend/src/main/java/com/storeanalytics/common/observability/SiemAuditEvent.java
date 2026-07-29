package com.storeanalytics.common.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * A versioned, fail-closed boundary for events exported to a SIEM.
 */
public final class SiemAuditEvent {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_EVENT_FIELDS = 8;
    private static final int MAX_MESSAGE_LENGTH = 96;
    private static final Pattern SYMBOL = Pattern.compile(
            "[a-z][a-z0-9_]{0,63}"
    );
    private static final Pattern REFERENCE = Pattern.compile(
            "h1_[0-9a-f]{24}"
    );
    private static final Pattern KEY_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,31}"
    );
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "event_schema_version",
            "event_category",
            "event_type",
            "event_outcome",
            "event_severity",
            "pseudonym_key_id"
    );

    private SiemAuditEvent() {
    }

    public enum Category {
        SECURITY("security"),
        BUSINESS_AUDIT("business_audit");

        private final String tag;

        Category(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    public enum Outcome {
        SUCCESS("success"),
        FAILURE("failure"),
        UNKNOWN("unknown");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    public enum Severity {
        INFO("info"),
        WARN("warn");

        private final String tag;

        Severity(String tag) {
            this.tag = tag;
        }

        String tag() {
            return tag;
        }
    }

    public static final class Definition {

        private final Category category;
        private final String type;
        private final Outcome outcome;
        private final Severity severity;
        private final Set<String> requiredFields;
        private final Set<String> optionalFields;
        private final Set<String> allowedFields;

        public Definition(
                Category category,
                String type,
                Outcome outcome,
                Severity severity,
                Set<String> requiredFields,
                Set<String> optionalFields
        ) {
            this.category = requireNonNull(category, "category");
            this.type = requireSymbol(type, "event type");
            this.outcome = requireNonNull(outcome, "outcome");
            this.severity = requireNonNull(severity, "severity");
            this.requiredFields = Set.copyOf(requiredFields);
            this.optionalFields = Set.copyOf(optionalFields);
            validateFieldDefinition();
            LinkedHashMap<String, Boolean> allowed = new LinkedHashMap<>();
            this.requiredFields.forEach(field -> allowed.put(field, true));
            this.optionalFields.forEach(field -> allowed.put(field, true));
            this.allowedFields = Set.copyOf(allowed.keySet());
        }

        public Event create(
                String pseudonymKeyId,
                Map<String, ?> fields
        ) {
            if (pseudonymKeyId == null
                    || !KEY_ID.matcher(pseudonymKeyId).matches()) {
                throw new IllegalArgumentException(
                        "invalid pseudonym key identifier"
                );
            }
            Map<String, ?> supplied = requireNonNull(fields, "fields");
            if (!supplied.keySet().containsAll(requiredFields)) {
                throw new IllegalArgumentException(
                        "missing required SIEM event field"
                );
            }
            if (!allowedFields.containsAll(supplied.keySet())) {
                throw new IllegalArgumentException(
                        "unexpected SIEM event field"
                );
            }
            LinkedHashMap<String, Object> validated = new LinkedHashMap<>();
            supplied.forEach((name, value) -> {
                validateFieldValue(name, value);
                validated.put(name, value);
            });
            return new Event(
                    this,
                    pseudonymKeyId,
                    Collections.unmodifiableMap(validated)
            );
        }

        private void validateFieldDefinition() {
            if (requiredFields.size() + optionalFields.size()
                    > MAX_EVENT_FIELDS) {
                throw new IllegalArgumentException(
                        "too many SIEM event fields"
                );
            }
            for (String field : requiredFields) {
                validateFieldName(field);
                if (optionalFields.contains(field)) {
                    throw new IllegalArgumentException(
                            "SIEM event field cannot be required and optional"
                    );
                }
            }
            optionalFields.forEach(SiemAuditEvent::validateFieldName);
        }
    }

    public static final class Event {

        private final Definition definition;
        private final String pseudonymKeyId;
        private final Map<String, Object> fields;

        private Event(
                Definition definition,
                String pseudonymKeyId,
                Map<String, Object> fields
        ) {
            this.definition = definition;
            this.pseudonymKeyId = pseudonymKeyId;
            this.fields = fields;
        }

        public void log(Logger logger, String message) {
            requireNonNull(logger, "logger");
            validateMessage(message);
            LoggingEventBuilder builder = switch (definition.severity) {
                case INFO -> logger.atInfo();
                case WARN -> logger.atWarn();
            };
            builder.addKeyValue("event_schema_version", SCHEMA_VERSION)
                    .addKeyValue(
                            "event_category",
                            definition.category.tag()
                    )
                    .addKeyValue("event_type", definition.type)
                    .addKeyValue(
                            "event_outcome",
                            definition.outcome.tag()
                    )
                    .addKeyValue(
                            "event_severity",
                            definition.severity.tag()
                    )
                    .addKeyValue("pseudonym_key_id", pseudonymKeyId);
            fields.forEach(builder::addKeyValue);
            builder.log(message);
        }
    }

    private static void validateFieldName(String field) {
        requireSymbol(field, "field name");
        if (ENVELOPE_FIELDS.contains(field)) {
            throw new IllegalArgumentException(
                    "SIEM event field collides with the envelope"
            );
        }
    }

    private static void validateFieldValue(String name, Object value) {
        if (value instanceof String text) {
            Pattern expected = name.endsWith("_ref") ? REFERENCE : SYMBOL;
            if (!expected.matcher(text).matches()) {
                throw new IllegalArgumentException(
                        "invalid SIEM event field value"
                );
            }
            return;
        }
        if (value instanceof Integer number && number >= 0) {
            return;
        }
        if (value instanceof Long number && number >= 0) {
            return;
        }
        if (value instanceof Boolean) {
            return;
        }
        throw new IllegalArgumentException("unsupported SIEM event field value");
    }

    private static String requireSymbol(String value, String label) {
        if (value == null || !SYMBOL.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static void validateMessage(String message) {
        if (message == null || message.isBlank()
                || message.length() > MAX_MESSAGE_LENGTH
                || message.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid SIEM event message");
        }
    }
}
