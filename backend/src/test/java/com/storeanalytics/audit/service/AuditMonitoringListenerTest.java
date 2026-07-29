package com.storeanalytics.audit.service;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.storeanalytics.common.config.SecurityTelemetryProperties;
import com.storeanalytics.common.security.SecurityPseudonymizer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AuditMonitoringListenerTest {

    @Test
    void emitsOnlyPseudonymousReferencesForCommittedAuditEvent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditMonitoringListener listener = new AuditMonitoringListener(
                pseudonymizer(), registry
        );
        UUID actorId = UUID.randomUUID();
        String targetId = "sensitive-target@example.com";
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("BUSINESS_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            listener.afterCommit(new AuditMonitoringEvent(
                    AuditAction.PAYROLL_APPROVED,
                    actorId,
                    targetId
            ));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        ILoggingEvent event = appender.list.getFirst();
        Map<String, String> fields = event.getKeyValuePairs().stream()
                .collect(toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(fields).containsEntry("event_schema_version", "1")
                .containsEntry("event_category", "business_audit")
                .containsEntry("event_type", "payroll_approved")
                .containsEntry("event_outcome", "success")
                .containsEntry("event_severity", "info")
                .containsEntry("audit_category", "payroll")
                .containsEntry("pseudonym_key_id", "test-v1");
        assertThat(fields.get("target_ref")).matches("h1_[0-9a-f]{24}");
        assertThat(fields.get("actor_ref")).matches("h1_[0-9a-f]{24}");
        assertThat(fields.toString())
                .doesNotContain(actorId.toString())
                .doesNotContain(targetId);
        assertThat(registry.counter(
                AuditMonitoringListener.EVENT_METRIC,
                "category", "payroll",
                "action", "payroll_approved"
        ).count()).isOne();
    }

    @Test
    void keepsAlertCategoriesBoundedAndExplicit() {
        assertThat(AuditMonitoringListener.category(AuditAction.USER_CHANGED))
                .isEqualTo("user_administration");
        assertThat(AuditMonitoringListener.category(
                AuditAction.BOOTSTRAP_ADMIN_CREATED
        )).isEqualTo("user_administration");
        assertThat(AuditMonitoringListener.category(
                AuditAction.BREAK_GLASS_LOGIN_SUCCEEDED
        )).isEqualTo("break_glass");
        assertThat(AuditMonitoringListener.category(
                AuditAction.SCHEDULED_SYNC_STARTED
        )).isEqualTo("synchronization");
        assertThat(AuditMonitoringListener.category(
                AuditAction.REPORT_BACKFILL_REQUESTED
        )).isEqualTo("report_backfill");
        assertThat(AuditMonitoringListener.category(
                AuditAction.TECHNICAL_DATA_RETENTION_COMPLETED
        )).isEqualTo("retention");
    }

    private SecurityPseudonymizer pseudonymizer() {
        return new SecurityPseudonymizer(new SecurityTelemetryProperties(
                "01234567890123456789012345678901",
                "test-v1"
        ));
    }
}
