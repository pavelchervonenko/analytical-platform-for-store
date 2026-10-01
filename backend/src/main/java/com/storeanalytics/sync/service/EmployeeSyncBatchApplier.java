package com.storeanalytics.sync.service;

import com.storeanalytics.metrics.repository.SellerMembershipHistoryWriter;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryWriter.ChangeSource;
import com.storeanalytics.sync.model.SyncRun;
import com.storeanalytics.sync.model.SyncStatus;
import com.storeanalytics.sync.repository.SyncRunRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies a fully fetched employee observation and temporal roster in one DB transaction. */
@Service
class EmployeeSyncBatchApplier {

    private final EmployeeSyncPersistence persistence;
    private final SyncRunRepository runs;
    private final SellerMembershipHistoryWriter membership;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    EmployeeSyncBatchApplier(EmployeeSyncPersistence persistence, SyncRunRepository runs,
                             SellerMembershipHistoryWriter membership, JdbcTemplate jdbc,
                             EntityManager entityManager, Clock clock) {
        this.persistence = persistence;
        this.runs = runs;
        this.membership = membership;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    EmployeeSyncResult apply(UUID connectionId, UUID syncRunId, SyncExecutionContext context,
                             List<StoreEmployeeBatch> batches) {
        UUID connection = java.util.Objects.requireNonNull(connectionId, "connectionId");
        UUID runId = java.util.Objects.requireNonNull(syncRunId, "syncRunId");
        List<StoreEmployeeBatch> fetched = List.copyOf(batches);
        lockConnection(connection);
        SyncRun run = runs.findById(runId).orElseThrow(() ->
                new IllegalArgumentException("Employee sync run disappeared"));
        if (run.getStatus() != SyncStatus.RUNNING || !connection.equals(run.getConnection().getId())
                || !java.util.Objects.equals(context.syncJobId(), run.getSyncJobId())) {
            throw new IllegalStateException("Employee sync run is no longer publishable");
        }
        Instant startedAt = jdbc.queryForObject("SELECT started_at FROM sync_runs WHERE id = ?",
                Timestamp.class, runId).toInstant();
        Timestamp latestSuccess = jdbc.queryForObject("""
                SELECT max(started_at) FROM sync_runs
                WHERE connection_id = ? AND sync_scope = 'EMPLOYEES'
                  AND status = 'SUCCESS' AND id <> ?
                """, Timestamp.class, connection, runId);
        if (latestSuccess != null && !latestSuccess.toInstant().isBefore(startedAt)) {
            throw new IllegalStateException("A newer employee observation was already published");
        }
        verifyJobLease(connection, context, false);

        List<LockedStore> lockedStores = jdbc.query("""
                SELECT id, is_active FROM stores WHERE connection_id = ? ORDER BY id FOR UPDATE
                """, (row, index) -> new LockedStore(row.getObject("id", UUID.class),
                        row.getBoolean("is_active")), connection);
        List<UUID> storeIds = lockedStores.stream().map(LockedStore::id).toList();
        Set<UUID> expected = new HashSet<>();
        for (LockedStore store : lockedStores) {
            if (store.active()) {
                expected.add(store.id());
            }
        }
        Set<UUID> received = new HashSet<>();
        for (StoreEmployeeBatch batch : fetched) {
            if (!expected.contains(batch.store().getId())
                    || !received.add(batch.store().getId())) {
                throw new IllegalStateException("Employee batch contains an unexpected store");
            }
        }
        if (!received.equals(expected)) {
            throw new IllegalStateException("Active store roster changed during employee fetch");
        }
        int totalFetched = 0;
        int created = 0;
        int updated = 0;
        int skipped = 0;
        Set<UUID> globallySeen = new HashSet<>();
        Map<UUID, Set<UUID>> seenByStore = new HashMap<>();
        for (StoreEmployeeBatch batch : fetched) {
            Set<UUID> storeSeen = new HashSet<>();
            for (var employee : batch.employees()) {
                totalFetched++;
                EmployeeRecordWriteResult result = persistence.synchronize(
                        runId, batch.store().getId(), employee);
                if (!storeSeen.add(result.employeeId())) {
                    throw new IllegalStateException("Employee batch contains duplicate identities");
                }
                globallySeen.add(result.employeeId());
                switch (result.outcome()) {
                    case CREATED -> created++;
                    case UPDATED -> updated++;
                    case SKIPPED -> skipped++;
                    default -> throw new IllegalStateException("Unsupported employee write result");
                }
            }
            if (seenByStore.putIfAbsent(batch.store().getId(), Set.copyOf(storeSeen)) != null) {
                throw new IllegalStateException("Employee batch contains duplicate stores");
            }
        }
        int assignmentsDeactivated = 0;
        for (StoreEmployeeBatch batch : fetched) {
            assignmentsDeactivated += persistence.deactivateMissingAssignments(
                    batch.store().getId(), seenByStore.get(batch.store().getId()));
        }
        int employeesDeactivated = persistence.deactivateMissingEmployees(connection, globallySeen);
        // JDBC history reads must see the complete JPA projection, including global deactivations.
        entityManager.flush();
        for (UUID storeId : storeIds) {
            membership.reconcileStore(storeId, ChangeSource.SYNC, null, runId);
        }
        verifyJobLease(connection, context, true);
        run.complete(totalFetched, created, updated, skipped, clock.instant());
        runs.saveAndFlush(run);
        return EmployeeSyncResult.from(run, assignmentsDeactivated, employeesDeactivated);
    }

    private void lockConnection(UUID connectionId) {
        List<UUID> locked = jdbc.query("""
                SELECT id FROM integration_connections WHERE id = ? FOR UPDATE
                """, (row, index) -> row.getObject("id", UUID.class), connectionId);
        if (locked.size() != 1) {
            throw new IllegalArgumentException("Employee sync connection is unavailable");
        }
    }

    private void verifyJobLease(UUID connectionId, SyncExecutionContext context, boolean lockForCommit) {
        if (context.syncJobId() == null) {
            return;
        }
        if (context.jobAttempt() == null) {
            throw new IllegalStateException("Employee sync job attempt is not fenced");
        }
        List<Boolean> valid = jdbc.query("""
                SELECT status = 'RUNNING' AND phase = 'EMPLOYEES'
                    AND connection_id = ? AND attempt_count = ?
                    AND NOT cancel_requested AND lease_until > clock_timestamp() AS valid
                FROM sync_jobs WHERE id = ? %s
                """.formatted(lockForCommit ? "FOR UPDATE" : ""),
                (row, index) -> row.getBoolean("valid"), connectionId,
                context.jobAttempt(), context.syncJobId());
        if (valid.size() != 1 || !valid.getFirst()) {
            throw new IllegalStateException("Employee sync job lease is no longer current");
        }
    }

    private record LockedStore(UUID id, boolean active) {
    }
}
