package com.storeanalytics.maintenance;

import com.storeanalytics.audit.service.AuditAction;
import com.storeanalytics.audit.service.AuditEntityType;
import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.audit.service.AuditTarget;
import com.storeanalytics.common.config.DataRetentionProperties;
import com.storeanalytics.common.config.SyncProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataRetentionService {

    private final DataRetentionRepository repository;
    private final DataRetentionProperties properties;
    private final SyncProperties syncProperties;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public DataRetentionService(
            DataRetentionRepository repository,
            DataRetentionProperties properties,
            SyncProperties syncProperties,
            AuditLogService auditLogService,
            Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.syncProperties = syncProperties;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    @Transactional
    public DataRetentionRunResult run() {
        requireDeletionReadiness();
        UUID runId = UUID.randomUUID();
        boolean dryRun = !properties.deletionEnabled();
        if (!repository.tryAcquireLock()) {
            return DataRetentionRunResult.skipped(runId, dryRun);
        }

        Cutoffs cutoffs = cutoffs();
        Map<String, Long> candidates = candidates(cutoffs);
        Map<String, Long> affected = dryRun
                ? Map.of() : applyRetention(cutoffs);
        Map<String, Long> remainingCandidates = dryRun
                ? candidates : candidates(cutoffs);

        DataRetentionRunResult result = new DataRetentionRunResult(
                runId,
                true,
                dryRun,
                candidates,
                affected,
                remainingCandidates
        );
        auditLogService.recordSystem(
                null,
                AuditAction.TECHNICAL_DATA_RETENTION_COMPLETED,
                new AuditTarget(AuditEntityType.DATA_RETENTION_RUN, runId),
                "Scheduled technical data retention maintenance",
                null,
                Map.of(
                        "mode", dryRun ? "DRY_RUN" : "DELETE",
                        "candidates", candidates,
                        "affected", affected,
                        "remainingCandidates", remainingCandidates,
                        "policyApprovalReference",
                        properties.deletionAuthorization().policyApprovalReference(),
                        "backupCheckpointReference",
                        properties.deletionAuthorization().backupCheckpointReference()
                )
        );
        return result;
    }

    private void requireDeletionReadiness() {
        if (!properties.deletionEnabled()) {
            return;
        }
        Instant now = clock.instant();
        DataRetentionProperties.DeletionAuthorization authorization =
                properties.deletionAuthorization();
        Instant restoreTestedAt = authorization.restoreTestedAt();
        if (restoreTestedAt.isAfter(now)) {
            throw new IllegalStateException(
                    "retention restore evidence cannot be dated in the future"
            );
        }
        if (restoreTestedAt.isBefore(
                now.minus(authorization.maximumRestoreTestAge())
        )) {
            throw new IllegalStateException(
                    "retention restore evidence is older than the configured maximum age"
            );
        }
    }

    private Cutoffs cutoffs() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(properties.zone()));
        LocalDate oldestPermittedBackfill = today.minusDays(
                syncProperties.maximumBackfillDays()
        );
        LocalDate monthlyCutoff = today
                .minus(properties.dailyInventoryRetention())
                .withDayOfMonth(1);
        if (!monthlyCutoff.isBefore(oldestPermittedBackfill)) {
            throw new IllegalStateException(
                    "daily inventory retention must exceed maximum sync backfill"
            );
        }
        Instant detailedCutoff = today
                .minus(properties.detailedInventoryRetention())
                .atStartOfDay(properties.zone())
                .toInstant();
        return new Cutoffs(
                now,
                now.minus(properties.normalizedRawRetention()),
                now.minus(properties.problemRawRetention()),
                now.minus(properties.successfulSyncRunRetention()),
                now.minus(properties.unsuccessfulSyncRunRetention()),
                now.minus(properties.successfulSyncJobRetention()),
                now.minus(properties.unsuccessfulSyncJobRetention()),
                now.minus(properties.closedQualityIssueRetention()),
                detailedCutoff,
                monthlyCutoff
        );
    }

    private Map<String, Long> candidates(Cutoffs cutoff) {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put(
                "legacy_raw_payload_versions",
                repository.countLegacyRawVersions()
        );
        result.put(
                "raw_record_versions",
                repository.countRawVersions(cutoff.normalizedRaw(), cutoff.problemRaw())
        );
        result.put(
                "sync_runs",
                repository.countSyncRuns(
                        cutoff.successfulSyncRun(),
                        cutoff.unsuccessfulSyncRun()
                )
        );
        result.put(
                "sync_run_errors",
                repository.countSyncRunErrors(
                        cutoff.successfulSyncRun(),
                        cutoff.unsuccessfulSyncRun()
                )
        );
        result.put(
                "sync_jobs",
                repository.countSyncJobs(
                        cutoff.successfulSyncJob(),
                        cutoff.unsuccessfulSyncJob()
                )
        );
        result.put(
                "inventory_history_rows",
                repository.countDetailedInventory(cutoff.detailedInventory())
        );
        result.put(
                "inventory_daily_rows",
                repository.countDailyInventory(cutoff.dailyInventory())
        );
        result.put(
                "closed_quality_issues",
                repository.countClosedQualityIssues(cutoff.closedQualityIssue())
        );
        result.put(
                "audit_log",
                repository.countExpiredAuditEntries(cutoff.now())
        );
        return Map.copyOf(result);
    }

    private Map<String, Long> applyRetention(Cutoffs cutoff) {
        Map<String, Long> result = new LinkedHashMap<>();
        RetentionBatchResult daily = repository.rollupDetailedInventory(
                cutoff.detailedInventory(),
                properties.zone(),
                properties.rollupBatchSize()
        );
        result.put("inventory_daily_rollups", daily.rollups());
        result.put("inventory_history_rows", daily.deleted());

        RetentionBatchResult monthly = repository.rollupDailyInventory(
                cutoff.dailyInventory(),
                properties.rollupBatchSize()
        );
        result.put("inventory_monthly_rollups", monthly.rollups());
        result.put("inventory_daily_rows", monthly.deleted());

        result.put(
                "raw_record_versions",
                repository.purgeRawVersions(
                        cutoff.normalizedRaw(),
                        cutoff.problemRaw(),
                        properties.deleteBatchSize()
                )
        );

        SyncRunPurgeResult syncRuns = repository.purgeSyncRuns(
                cutoff.successfulSyncRun(),
                cutoff.unsuccessfulSyncRun(),
                properties.deleteBatchSize()
        );
        result.put("sync_runs", syncRuns.runs());
        result.put("sync_run_errors", syncRuns.errors());

        result.put(
                "sync_jobs",
                repository.purgeSyncJobs(
                        cutoff.successfulSyncJob(),
                        cutoff.unsuccessfulSyncJob(),
                        properties.deleteBatchSize()
                )
        );
        result.put(
                "closed_quality_issues",
                repository.purgeClosedQualityIssues(
                        cutoff.closedQualityIssue(),
                        properties.deleteBatchSize()
                )
        );
        result.put(
                "audit_log",
                repository.purgeExpiredAuditEntries(
                        cutoff.now(),
                        properties.deleteBatchSize()
                )
        );
        return Map.copyOf(result);
    }

    private record Cutoffs(
            Instant now,
            Instant normalizedRaw,
            Instant problemRaw,
            Instant successfulSyncRun,
            Instant unsuccessfulSyncRun,
            Instant successfulSyncJob,
            Instant unsuccessfulSyncJob,
            Instant closedQualityIssue,
            Instant detailedInventory,
            LocalDate dailyInventory
    ) {
    }
}
