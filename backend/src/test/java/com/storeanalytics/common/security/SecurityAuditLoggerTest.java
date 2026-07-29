package com.storeanalytics.common.security;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.storeanalytics.common.config.SecurityTelemetryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SecurityAuditLoggerTest {

    @Test
    void emitsBoundedStructuredFieldsWithoutRawIdentityData() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityPseudonymizer pseudonymizer = new SecurityPseudonymizer(
                new SecurityTelemetryProperties(
                        "01234567890123456789012345678901",
                        "test-v1"
                )
        );
        SecurityAuditLogger auditLogger = new SecurityAuditLogger(
                pseudonymizer, registry
        );
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("SECURITY_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            auditLogger.loginFailed(
                    "Sensitive.User@example.com",
                    new ClientAddress("203.0.113.25", "203.0.113.25")
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        Map<String, String> fields = event.getKeyValuePairs().stream()
                .collect(toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).isEqualTo("Security event");
        assertThat(fields).containsEntry("event_schema_version", "1")
                .containsEntry("event_category", "security")
                .containsEntry("event_type", "login_failed")
                .containsEntry("event_outcome", "failure")
                .containsEntry("event_severity", "warn")
                .containsEntry("pseudonym_key_id", "test-v1");
        assertThat(fields.get("email_ref")).matches("h1_[0-9a-f]{24}");
        assertThat(fields.get("client_ref")).matches("h1_[0-9a-f]{24}");
        assertThat(fields.toString())
                .doesNotContain("Sensitive.User@example.com")
                .doesNotContain("203.0.113.25")
                .doesNotContain("01234567890123456789012345678901");
        assertThat(registry.counter(
                SecurityAuditLogger.EVENT_METRIC,
                "type", "login_failed"
        ).count()).isOne();
    }

    @Test
    void mapsUntrustedReasonAndActionToBoundedValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityAuditLogger auditLogger = new SecurityAuditLogger(
                new SecurityPseudonymizer(new SecurityTelemetryProperties(
                        "01234567890123456789012345678901", "test-v1"
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
            auditLogger.sessionRejected(userId, "password=secret\nforged=true");
            auditLogger.userAdministration(
                    "reset_password\nforged=true", userId, UUID.randomUUID()
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(2);
        Map<String, String> sessionFields = fields(appender.list.get(0));
        Map<String, String> administrationFields = fields(
                appender.list.get(1)
        );
        assertThat(sessionFields).containsEntry("reason", "unknown");
        assertThat(administrationFields).containsEntry("action", "unknown");
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsOnly("Security event");
        assertThat(sessionFields.toString())
                .doesNotContain("password=secret")
                .doesNotContain("forged");
        assertThat(administrationFields.toString())
                .doesNotContain("reset_password")
                .doesNotContain("forged");

        assertThat(registry.counter(
                SecurityAuditLogger.EVENT_METRIC,
                "type", "session_rejected"
        ).count()).isOne();
        assertThat(registry.counter(
                SecurityAuditLogger.EVENT_METRIC,
                "type", "user_administration"
        ).count()).isOne();
    }

    @Test
    void emitsDedicatedBootstrapAndBreakGlassSignalsWithoutRawIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SecurityAuditLogger auditLogger = new SecurityAuditLogger(
                new SecurityPseudonymizer(new SecurityTelemetryProperties(
                        "01234567890123456789012345678901", "test-v1"
                )),
                registry
        );
        UUID userId = UUID.randomUUID();
        String address = "203.0.113.25";
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("SECURITY_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            auditLogger.bootstrapAdministratorCreated(userId);
            auditLogger.bootstrapAdministratorSkipped();
            auditLogger.breakGlassLoginSucceeded(
                    userId, new ClientAddress(address, address)
            );
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).hasSize(3);
        assertThat(fields(appender.list.get(0)))
                .containsEntry("event_type", "bootstrap_admin_created")
                .containsEntry("event_outcome", "success")
                .containsEntry("event_severity", "warn");
        assertThat(fields(appender.list.get(1)))
                .containsEntry("event_type", "bootstrap_admin_skipped")
                .containsEntry("event_outcome", "unknown")
                .containsEntry("event_severity", "warn")
                .containsEntry("reason", "users_exist");
        Map<String, String> breakGlassFields = fields(appender.list.get(2));
        assertThat(breakGlassFields)
                .containsEntry("event_type", "break_glass_login_succeeded");
        assertThat(breakGlassFields.get("user_ref"))
                .matches("h1_[0-9a-f]{24}");
        assertThat(breakGlassFields.get("client_ref"))
                .matches("h1_[0-9a-f]{24}");
        assertThat(breakGlassFields.toString())
                .doesNotContain(userId.toString())
                .doesNotContain(address);
        assertThat(registry.counter(
                SecurityAuditLogger.EVENT_METRIC,
                "type", "break_glass_login_succeeded"
        ).count()).isOne();
    }

    @Test
    void everySecurityPublisherConformsToItsDeclaredSchema() {
        SecurityAuditLogger auditLogger = new SecurityAuditLogger(
                new SecurityPseudonymizer(new SecurityTelemetryProperties(
                        "01234567890123456789012345678901",
                        "test-v1"
                )),
                new SimpleMeterRegistry()
        );
        UUID userId = UUID.randomUUID();
        ClientAddress clientAddress = new ClientAddress(
                "203.0.113.25",
                "203.0.113.25"
        );
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("SECURITY_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            auditLogger.loginSucceeded(userId, clientAddress);
            auditLogger.loginThrottled("user@example.com", clientAddress);
            auditLogger.authenticationRequired(clientAddress);
            auditLogger.accessDenied(null, clientAddress);
            auditLogger.csrfRejected(userId, clientAddress);
            auditLogger.passwordChanged(userId);
            auditLogger.sessionExpired(clientAddress);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(event -> fields(event).get("event_type"))
                .containsExactly(
                        "login_succeeded",
                        "login_throttled",
                        "authentication_required",
                        "access_denied",
                        "csrf_rejected",
                        "password_changed",
                        "session_expired"
                );
        assertThat(appender.list).allSatisfy(event -> assertThat(fields(event))
                .containsEntry("event_schema_version", "1")
                .containsKeys(
                        "event_category",
                        "event_outcome",
                        "event_severity",
                        "pseudonym_key_id"
                ));
        assertThat(appender.list.toString())
                .doesNotContain(userId.toString())
                .doesNotContain("203.0.113.25")
                .doesNotContain("user@example.com");
    }

    private Map<String, String> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream()
                .collect(toMap(
                        pair -> pair.key, pair -> String.valueOf(pair.value)
                ));
    }
}
