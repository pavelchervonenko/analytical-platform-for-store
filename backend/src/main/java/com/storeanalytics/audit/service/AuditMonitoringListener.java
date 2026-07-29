package com.storeanalytics.audit.service;

import com.storeanalytics.common.observability.SiemAuditEvent;
import com.storeanalytics.common.security.SecurityPseudonymizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AuditMonitoringListener {

    static final String EVENT_METRIC = "storeanalytics.audit.events";
    private static final Logger LOGGER = LoggerFactory.getLogger(
            "BUSINESS_AUDIT"
    );

    private final SecurityPseudonymizer pseudonymizer;
    private final Map<AuditAction, Counter> counters;
    private final Map<AuditAction, SiemAuditEvent.Definition> definitions;

    public AuditMonitoringListener(
            SecurityPseudonymizer pseudonymizer,
            MeterRegistry meterRegistry
    ) {
        this.pseudonymizer = pseudonymizer;
        this.counters = new EnumMap<>(AuditAction.class);
        this.definitions = new EnumMap<>(AuditAction.class);
        for (AuditAction action : AuditAction.values()) {
            String actionTag = actionTag(action);
            counters.put(action, Counter.builder(EVENT_METRIC)
                    .description("Committed persistent business audit events")
                    .tag("category", category(action))
                    .tag("action", actionTag)
                    .register(meterRegistry));
            definitions.put(action, new SiemAuditEvent.Definition(
                    SiemAuditEvent.Category.BUSINESS_AUDIT,
                    actionTag,
                    SiemAuditEvent.Outcome.SUCCESS,
                    SiemAuditEvent.Severity.INFO,
                    Set.of("audit_category", "target_ref"),
                    Set.of("actor_ref")
            ));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(AuditMonitoringEvent event) {
        AuditAction action = event.action();
        String actionTag = actionTag(action);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("audit_category", category(action));
        fields.put("target_ref", pseudonymizer.reference(
                "audit_target:" + actionTag,
                event.targetId()
        ));
        if (event.actorUserId() != null) {
            fields.put("actor_ref", pseudonymizer.reference(
                    "user",
                    event.actorUserId().toString()
            ));
        }

        SiemAuditEvent.Event monitoringEvent = definitions.get(action).create(
                pseudonymizer.keyId(),
                fields
        );
        counters.get(action).increment();
        monitoringEvent.log(LOGGER, "Committed business audit event");
    }

    static String category(AuditAction action) {
        return switch (action) {
            case USER_CREATED, USER_CHANGED, USER_STORE_ACCESS_CHANGED,
                    USER_PASSWORD_RESET,
                    BOOTSTRAP_ADMIN_CREATED -> "user_administration";
            case BREAK_GLASS_LOGIN_SUCCEEDED -> "break_glass";
            case PAYROLL_SCHEME_CREATED, PAYROLL_PRODUCT_CLASSIFIED,
                    PAYROLL_CALCULATED, PAYROLL_RECALCULATED,
                    PAYROLL_REVISION_CREATED, PAYROLL_ADJUSTMENT_CREATED,
                    PAYROLL_ADJUSTMENT_VOIDED, PAYROLL_APPROVED,
                    PAYROLL_PAID -> "payroll";
            case MANUAL_SYNC_STARTED, SCHEDULED_SYNC_STARTED,
                    SYNC_JOB_CANCELLATION_REQUESTED -> "synchronization";
            case REPORT_BACKFILL_REQUESTED,
                    REPORT_BACKFILL_CANCELLATION_REQUESTED -> "report_backfill";
            case TECHNICAL_DATA_RETENTION_COMPLETED -> "retention";
            default -> "business";
        };
    }

    private static String actionTag(AuditAction action) {
        return action.name().toLowerCase(Locale.ROOT);
    }
}
