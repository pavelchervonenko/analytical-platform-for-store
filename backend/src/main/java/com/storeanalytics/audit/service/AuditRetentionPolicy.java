package com.storeanalytics.audit.service;

import com.storeanalytics.audit.model.AuditRetention;
import com.storeanalytics.audit.model.AuditRetentionClass;
import com.storeanalytics.common.config.DataRetentionProperties;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

@Component
public class AuditRetentionPolicy {

    private final DataRetentionProperties properties;

    public AuditRetentionPolicy(DataRetentionProperties properties) {
        this.properties = properties;
    }

    public AuditRetention retention(AuditAction action, Instant createdAt) {
        AuditRetentionClass retentionClass = retentionClass(action);
        return new AuditRetention(
                retentionClass,
                retainUntil(retentionClass, createdAt)
        );
    }

    public AuditRetentionClass retentionClass(AuditAction action) {
        return switch (action) {
            case PERFORMANCE_PLAN_CHANGED,
                    WORK_SCHEDULE_REPLACED,
                    PAYROLL_SCHEME_CREATED,
                    PAYROLL_PRODUCT_CLASSIFIED,
                    PAYROLL_CALCULATED,
                    PAYROLL_RECALCULATED,
                    PAYROLL_REVISION_CREATED,
                    PAYROLL_ADJUSTMENT_CREATED,
                    PAYROLL_ADJUSTMENT_VOIDED,
                    PAYROLL_APPROVED,
                    PAYROLL_PAID,
                    MONTHLY_REPORT_FINALIZED,
                    ANNUAL_REPORT_FINALIZED,
                    REPORT_BACKFILL_REQUESTED,
                    REPORT_BACKFILL_CANCELLATION_REQUESTED,
                    RETURN_RECOVERY_REQUESTED ->
                    AuditRetentionClass.FINANCIAL;
            case USER_CREATED,
                    USER_CHANGED,
                    USER_STORE_ACCESS_CHANGED,
                    USER_PASSWORD_RESET,
                    BOOTSTRAP_ADMIN_CREATED,
                    BREAK_GLASS_LOGIN_SUCCEEDED,
                    TELEGRAM_LINK_ISSUED,
                    TELEGRAM_LINK_PENDING,
                    TELEGRAM_LINK_CONFIRMED,
                    TELEGRAM_LINK_REVOKED,
                    TELEGRAM_DELIVERY_SETTINGS_CHANGED,
                    TELEGRAM_BOT_BLOCKED,
                    TELEGRAM_BOT_UNBLOCKED,
                    TELEGRAM_DELIVERY_RESEND_REQUESTED -> AuditRetentionClass.SECURITY;
            case MANUAL_SYNC_STARTED,
                    SCHEDULED_SYNC_STARTED,
                    LLM_REGENERATION_REQUESTED,
                    LLM_JOB_CANCELLATION_REQUESTED,
                    SYNC_JOB_CANCELLATION_REQUESTED,
                    TECHNICAL_DATA_RETENTION_COMPLETED -> AuditRetentionClass.OPERATIONAL;
            case EMPLOYEE_RATING_PARTICIPATION_CHANGED,
                    EMPLOYEE_RATING_FINALIZED,
                    RATING_SCHEME_CREATED,
                    ANALYTICS_PRODUCT_CLASSIFIED -> AuditRetentionClass.BUSINESS;
        };
    }

    public Instant retainUntil(AuditRetentionClass retentionClass, Instant createdAt) {
        if (retentionClass == AuditRetentionClass.FINANCIAL) {
            return null;
        }
        Period retention = switch (retentionClass) {
            case SECURITY -> properties.audit().securityRetention();
            case BUSINESS -> properties.audit().businessRetention();
            case OPERATIONAL -> properties.audit().operationalRetention();
            case FINANCIAL -> throw new IllegalStateException(
                    "financial audit entries have no retention deadline"
            );
        };
        return createdAt.atZone(ZoneOffset.UTC).plus(retention).toInstant();
    }
}
