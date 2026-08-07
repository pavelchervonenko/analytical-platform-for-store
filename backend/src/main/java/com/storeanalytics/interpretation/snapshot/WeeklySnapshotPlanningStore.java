package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WeeklySnapshotPlanningStore {

    private final JdbcTemplate jdbcTemplate;

    public WeeklySnapshotPlanningStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<StoreTarget> activeStores(int limit) {
        require(limit >= 1 && limit <= 100, "limit must be between 1 and 100");
        return jdbcTemplate.query(
                """
                SELECT id, timezone
                FROM stores
                WHERE is_active = true AND source_system = 'LIVESKLAD'
                  AND connection_id IS NOT NULL
                ORDER BY id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> new StoreTarget(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("timezone")
                ),
                limit
        );
    }

    @Transactional(readOnly = true)
    public Optional<SourceSync> newestSuitableSource(
            UUID storeId,
            Instant requiredCoverage,
            Instant now
    ) {
        List<SourceSync> values = jdbcTemplate.query(
                """
                SELECT job.id, job.period_end, job.finished_at
                FROM stores store
                JOIN sync_jobs job ON job.connection_id = store.connection_id
                WHERE store.id = ? AND store.is_active = true
                  AND job.status = 'SUCCESS' AND job.finished_at IS NOT NULL
                  AND job.period_end >= ? AND job.finished_at <= ?
                ORDER BY job.finished_at DESC, job.created_at DESC, job.id DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> new SourceSync(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getTimestamp("period_end").toInstant(),
                        resultSet.getTimestamp("finished_at").toInstant()
                ),
                requireNonNull(storeId, "storeId"),
                Timestamp.from(requireNonNull(requiredCoverage, "requiredCoverage")),
                Timestamp.from(requireNonNull(now, "now"))
        );
        return values.stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<LatestSnapshot> latestSnapshot(
            UUID storeId,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd
    ) {
        List<LatestSnapshot> values = jdbcTemplate.query(
                """
                SELECT id, source_sync_job_id, source_sync_completed_at,
                       source_data_cutoff
                FROM analytics_snapshots
                WHERE store_id = ? AND snapshot_type = 'WEEKLY'
                  AND period_start = ? AND period_end = ?
                ORDER BY revision DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> new LatestSnapshot(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("source_sync_job_id", UUID.class),
                        resultSet.getTimestamp("source_sync_completed_at").toInstant(),
                        resultSet.getTimestamp("source_data_cutoff").toInstant()
                ),
                requireNonNull(storeId, "storeId"),
                requireNonNull(periodStart, "periodStart"),
                requireNonNull(periodEnd, "periodEnd")
        );
        return values.stream().findFirst();
    }

    public record StoreTarget(UUID storeId, String timezone) {

        public StoreTarget {
            requireNonNull(storeId, "storeId");
            if (timezone == null || timezone.isBlank()) {
                throw new IllegalArgumentException("timezone must contain text");
            }
        }
    }

    public record SourceSync(
            UUID syncJobId,
            Instant periodEnd,
            Instant completedAt
    ) {

        public SourceSync {
            requireNonNull(syncJobId, "syncJobId");
            requireNonNull(periodEnd, "periodEnd");
            requireNonNull(completedAt, "completedAt");
        }
    }

    public record LatestSnapshot(
            UUID snapshotId,
            UUID sourceSyncJobId,
            Instant sourceSyncCompletedAt,
            Instant sourceDataCutoff
    ) {

        public LatestSnapshot {
            requireNonNull(snapshotId, "snapshotId");
            requireNonNull(sourceSyncJobId, "sourceSyncJobId");
            requireNonNull(sourceSyncCompletedAt, "sourceSyncCompletedAt");
            requireNonNull(sourceDataCutoff, "sourceDataCutoff");
        }
    }
}
