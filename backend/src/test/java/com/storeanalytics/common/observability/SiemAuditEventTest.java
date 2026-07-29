package com.storeanalytics.common.observability;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SiemAuditEventTest {

    private static final String SAFE_REFERENCE = "h1_0123456789abcdef01234567";

    @Test
    void emitsVersionedEnvelopeAtDeclaredSeverity() {
        SiemAuditEvent.Definition definition = definition();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("SIEM_AUDIT_TEST");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            definition.create("test-v1", Map.of(
                    "subject_ref", SAFE_REFERENCE,
                    "reason", "policy_rejected"
            )).log(logger, "Bounded audit event");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        Map<String, String> fields = event.getKeyValuePairs().stream()
                .collect(toMap(
                        pair -> pair.key,
                        pair -> String.valueOf(pair.value)
                ));
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(fields)
                .containsEntry("event_schema_version", "1")
                .containsEntry("event_category", "security")
                .containsEntry("event_type", "policy_rejected")
                .containsEntry("event_outcome", "failure")
                .containsEntry("event_severity", "warn")
                .containsEntry("pseudonym_key_id", "test-v1")
                .containsEntry("subject_ref", SAFE_REFERENCE)
                .containsEntry("reason", "policy_rejected");
    }

    @Test
    void rejectsMissingAndUnexpectedFields() {
        SiemAuditEvent.Definition definition = definition();

        assertThatThrownBy(() -> definition.create(
                "test-v1",
                Map.of("reason", "policy_rejected")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required");
        assertThatThrownBy(() -> definition.create(
                "test-v1",
                Map.of(
                        "subject_ref", SAFE_REFERENCE,
                        "forged", "value"
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected");
    }

    @Test
    void rejectsRawReferencesAndUnboundedLabels() {
        SiemAuditEvent.Definition definition = definition();

        assertThatThrownBy(() -> definition.create(
                "test-v1",
                Map.of("subject_ref", "sensitive.user@example.com")
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field value");
        assertThatThrownBy(() -> definition.create(
                "test-v1",
                Map.of(
                        "subject_ref", SAFE_REFERENCE,
                        "reason", "safe\nforged=true"
                )
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field value");
    }

    @Test
    void rejectsEnvelopeCollisionsInDefinitions() {
        assertThatThrownBy(() -> new SiemAuditEvent.Definition(
                SiemAuditEvent.Category.SECURITY,
                "policy_rejected",
                SiemAuditEvent.Outcome.FAILURE,
                SiemAuditEvent.Severity.WARN,
                Set.of("event_type"),
                Set.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collides");
    }

    private SiemAuditEvent.Definition definition() {
        return new SiemAuditEvent.Definition(
                SiemAuditEvent.Category.SECURITY,
                "policy_rejected",
                SiemAuditEvent.Outcome.FAILURE,
                SiemAuditEvent.Severity.WARN,
                Set.of("subject_ref"),
                Set.of("reason")
        );
    }
}
