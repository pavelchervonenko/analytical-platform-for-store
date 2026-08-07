package com.storeanalytics.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.audit.model.AuditRetentionClass;
import com.storeanalytics.common.config.DataRetentionProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AuditRetentionPolicyTest {

    private final AuditRetentionPolicy policy = new AuditRetentionPolicy(properties());

    @Test
    void classifiesEveryActionIntoAnExplicitRetentionClass() {
        Set<AuditAction> financial = EnumSet.of(
                AuditAction.PERFORMANCE_PLAN_CHANGED,
                AuditAction.WORK_SCHEDULE_REPLACED,
                AuditAction.PAYROLL_SCHEME_CREATED,
                AuditAction.PAYROLL_PRODUCT_CLASSIFIED,
                AuditAction.PAYROLL_CALCULATED,
                AuditAction.PAYROLL_RECALCULATED,
                AuditAction.PAYROLL_REVISION_CREATED,
                AuditAction.PAYROLL_ADJUSTMENT_CREATED,
                AuditAction.PAYROLL_ADJUSTMENT_VOIDED,
                AuditAction.PAYROLL_APPROVED,
                AuditAction.PAYROLL_PAID,
                AuditAction.MONTHLY_REPORT_FINALIZED,
                AuditAction.ANNUAL_REPORT_FINALIZED,
                AuditAction.REPORT_BACKFILL_REQUESTED,
                AuditAction.REPORT_BACKFILL_CANCELLATION_REQUESTED
        );
        Set<AuditAction> security = EnumSet.of(
                AuditAction.USER_CREATED,
                AuditAction.USER_CHANGED,
                AuditAction.USER_STORE_ACCESS_CHANGED,
                AuditAction.USER_PASSWORD_RESET,
                AuditAction.BOOTSTRAP_ADMIN_CREATED,
                AuditAction.BREAK_GLASS_LOGIN_SUCCEEDED,
                AuditAction.TELEGRAM_LINK_ISSUED,
                AuditAction.TELEGRAM_LINK_PENDING,
                AuditAction.TELEGRAM_LINK_CONFIRMED,
                AuditAction.TELEGRAM_LINK_REVOKED,
                AuditAction.TELEGRAM_DELIVERY_SETTINGS_CHANGED,
                AuditAction.TELEGRAM_BOT_BLOCKED,
                AuditAction.TELEGRAM_BOT_UNBLOCKED,
                AuditAction.TELEGRAM_DELIVERY_RESEND_REQUESTED
        );
        Set<AuditAction> operational = EnumSet.of(
                AuditAction.MANUAL_SYNC_STARTED,
                AuditAction.SCHEDULED_SYNC_STARTED,
                AuditAction.LLM_REGENERATION_REQUESTED,
                AuditAction.LLM_JOB_CANCELLATION_REQUESTED,
                AuditAction.SYNC_JOB_CANCELLATION_REQUESTED,
                AuditAction.TECHNICAL_DATA_RETENTION_COMPLETED
        );

        assertThat(financial).allSatisfy(action -> assertThat(
                policy.retentionClass(action)
        ).isEqualTo(AuditRetentionClass.FINANCIAL));
        assertThat(security).allSatisfy(action -> assertThat(
                policy.retentionClass(action)
        ).isEqualTo(AuditRetentionClass.SECURITY));
        assertThat(operational).allSatisfy(action -> assertThat(
                policy.retentionClass(action)
        ).isEqualTo(AuditRetentionClass.OPERATIONAL));

        EnumSet<AuditAction> business = EnumSet.allOf(AuditAction.class);
        business.removeAll(financial);
        business.removeAll(security);
        business.removeAll(operational);
        assertThat(business).allSatisfy(action -> assertThat(
                policy.retentionClass(action)
        ).isEqualTo(AuditRetentionClass.BUSINESS));
    }

    @Test
    void calculatesStableDeadlinesAndKeepsFinancialAuditIndefinitely() {
        Instant createdAt = Instant.parse("2026-07-24T10:00:00Z");

        assertThat(policy.retention(AuditAction.PAYROLL_PAID, createdAt).retainUntil())
                .isNull();
        assertThat(policy.retention(AuditAction.USER_CHANGED, createdAt).retainUntil())
                .isEqualTo(Instant.parse("2031-07-24T10:00:00Z"));
        assertThat(policy.retention(
                AuditAction.EMPLOYEE_RATING_FINALIZED,
                createdAt
        ).retainUntil()).isEqualTo(Instant.parse("2029-07-24T10:00:00Z"));
        assertThat(policy.retention(
                AuditAction.MANUAL_SYNC_STARTED,
                createdAt
        ).retainUntil()).isEqualTo(Instant.parse("2027-07-24T10:00:00Z"));
    }

    private DataRetentionProperties properties() {
        return new DataRetentionProperties(
                false,
                10_000,
                1_000,
                Duration.ofDays(180),
                Duration.ofDays(365),
                Duration.ofDays(90),
                Duration.ofDays(365),
                Duration.ofDays(90),
                Duration.ofDays(180),
                Duration.ofDays(365),
                Period.ofMonths(13),
                Period.ofYears(3),
                ZoneId.of("Europe/Kaliningrad"),
                new DataRetentionProperties.Audit(
                        Period.ofYears(5),
                        Period.ofYears(3),
                        Period.ofYears(1)
                ),
                new DataRetentionProperties.DeletionAuthorization(
                        "UNAPPROVED",
                        "UNVERIFIED",
                        Instant.EPOCH,
                        Duration.ofDays(90)
                )
        );
    }
}
