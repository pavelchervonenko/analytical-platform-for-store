package com.storeanalytics.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.common.config.DataRetentionProperties;
import com.storeanalytics.common.config.SyncProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DataRetentionServiceTest {

    @Test
    void dryRunCountsCandidatesWithoutMutatingData() {
        DataRetentionRepository repository = mock(DataRetentionRepository.class);
        DataRetentionProperties properties = properties(false);
        SyncProperties syncProperties = mock(SyncProperties.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(repository.tryAcquireLock()).thenReturn(true);
        when(syncProperties.maximumBackfillDays()).thenReturn(730);
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-24T10:00:00Z"),
                ZoneOffset.UTC
        );
        DataRetentionService service = new DataRetentionService(
                repository,
                properties,
                syncProperties,
                auditLogService,
                clock
        );

        DataRetentionRunResult result = service.run();

        assertThat(result.lockAcquired()).isTrue();
        assertThat(result.dryRun()).isTrue();
        assertThat(result.affected()).isEmpty();
        assertThat(result.candidates()).containsOnlyKeys(
                "legacy_raw_payload_versions",
                "raw_record_versions",
                "sync_runs",
                "sync_run_errors",
                "sync_jobs",
                "inventory_history_rows",
                "inventory_daily_rows",
                "closed_quality_issues",
                "audit_log"
        );
        verify(repository, never()).rollupDetailedInventory(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(repository, never()).rollupDailyInventory(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(repository, never()).purgeRawVersions(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(repository, never()).purgeSyncRuns(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(repository, never()).purgeSyncJobs(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(repository, never()).purgeClosedQualityIssues(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(repository, never()).purgeExpiredAuditEntries(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
        verify(auditLogService).recordSystem(
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(
                        AuditAction.TECHNICAL_DATA_RETENTION_COMPLETED
                ),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyMap()
        );
    }

    @Test
    void enabledRunReportsRemainingCandidatesAfterTheBoundedPurge() {
        DataRetentionRepository repository = mock(DataRetentionRepository.class);
        SyncProperties syncProperties = mock(SyncProperties.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(repository.tryAcquireLock()).thenReturn(true);
        when(repository.countRawVersions(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(5L, 2L);
        when(syncProperties.maximumBackfillDays()).thenReturn(730);
        when(repository.rollupDetailedInventory(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        )).thenReturn(new RetentionBatchResult(0, 0));
        when(repository.rollupDailyInventory(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        )).thenReturn(new RetentionBatchResult(0, 0));
        when(repository.purgeSyncRuns(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        )).thenReturn(new SyncRunPurgeResult(0, 0));
        DataRetentionService service = new DataRetentionService(
                repository,
                properties(true),
                syncProperties,
                auditLogService,
                Clock.fixed(
                        Instant.parse("2026-07-24T10:00:00Z"),
                        ZoneOffset.UTC
                )
        );

        DataRetentionRunResult result = service.run();

        assertThat(result.dryRun()).isFalse();
        assertThat(result.candidates().get("raw_record_versions")).isEqualTo(5L);
        assertThat(result.remainingCandidates().get("raw_record_versions"))
                .isEqualTo(2L);
        verify(repository).purgeRawVersions(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(10_000)
        );
    }

    @Test
    void enabledRunRejectsStaleRestoreEvidenceBeforeAcquiringTheLock() {
        DataRetentionRepository repository = mock(DataRetentionRepository.class);
        DataRetentionService service = new DataRetentionService(
                repository,
                properties(true, Instant.parse("2026-01-01T00:00:00Z")),
                mock(SyncProperties.class),
                mock(AuditLogService.class),
                Clock.fixed(
                        Instant.parse("2026-07-24T10:00:00Z"),
                        ZoneOffset.UTC
                )
        );

        assertThatThrownBy(service::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restore evidence is older");
        verifyNoInteractions(repository);
    }

    private DataRetentionProperties properties(boolean deletionEnabled) {
        return properties(
                deletionEnabled,
                Instant.parse("2026-07-01T00:00:00Z")
        );
    }

    private DataRetentionProperties properties(
            boolean deletionEnabled,
            Instant restoreTestedAt
    ) {
        return new DataRetentionProperties(
                deletionEnabled,
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
                        deletionEnabled ? "policy-approval-2026-07" : "UNAPPROVED",
                        deletionEnabled ? "backup-checkpoint-2026-07" : "UNVERIFIED",
                        restoreTestedAt,
                        Duration.ofDays(90)
                )
        );
    }
}
