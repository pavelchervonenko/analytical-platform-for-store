package com.storeanalytics.common.security;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.storeanalytics.common.config.SecurityTelemetryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SessionRevocationSecurityAuditTest {

    @Test
    void emitsBoundedRevocationSignalWithoutSessionOrUserIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityAuditLogger auditLogger = new SecurityAuditLogger(
                new SecurityPseudonymizer(new SecurityTelemetryProperties(
                        "01234567890123456789012345678901",
                        "test-v1"
                )),
                registry
        );
        UUID userId = UUID.randomUUID();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("SECURITY_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            auditLogger.sessionsRevoked(
                    userId,
                    "single\nforged=true",
                    2
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(1);
        Map<String, String> fields = appender.list.getFirst()
                .getKeyValuePairs()
                .stream()
                .collect(toMap(
                        pair -> pair.key,
                        pair -> String.valueOf(pair.value)
                ));
        assertThat(fields)
                .containsEntry("event_type", "sessions_revoked")
                .containsEntry("scope", "unknown")
                .containsEntry("count", "2");
        assertThat(fields.get("user_ref")).matches("h1_[0-9a-f]{24}");
        assertThat(fields.toString())
                .doesNotContain(userId.toString())
                .doesNotContain("forged");
        assertThat(registry.counter(
                SecurityAuditLogger.EVENT_METRIC,
                "type", "sessions_revoked"
        ).count()).isOne();
    }

    @Test
    void rejectsNonPositiveCountInsteadOfPublishingMisleadingSignal() {
        SecurityAuditLogger auditLogger = new SecurityAuditLogger(
                new SecurityPseudonymizer(new SecurityTelemetryProperties(
                        "01234567890123456789012345678901",
                        "test-v1"
                )),
                new SimpleMeterRegistry()
        );

        assertThatThrownBy(() -> auditLogger.sessionsRevoked(
                UUID.randomUUID(),
                "single",
                0
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }
}
