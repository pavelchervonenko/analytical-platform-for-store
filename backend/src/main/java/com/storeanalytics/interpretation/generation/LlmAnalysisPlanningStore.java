package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LlmAnalysisPlanningStore {

    private final JdbcTemplate jdbcTemplate;

    public LlmAnalysisPlanningStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<SnapshotTarget> eligibleSnapshots(int limit) {
        require(limit >= 1 && limit <= 100, "limit must be between 1 and 100");
        return jdbcTemplate.query(
                """
                SELECT snapshot.id, snapshot.store_id, snapshot.revision,
                       snapshot.quality_status, snapshot.facts_hash, snapshot.created_at
                FROM analytics_snapshots snapshot
                JOIN stores store ON store.id = snapshot.store_id
                WHERE store.is_active = true
                  AND snapshot.snapshot_type = 'WEEKLY'
                  AND snapshot.quality_status IN ('READY', 'PARTIAL')
                  AND EXISTS (
                      SELECT 1 FROM analytics_snapshot_jobs source_job
                      WHERE source_job.result_snapshot_id = snapshot.id
                        AND source_job.status = 'SUCCESS'
                        AND source_job.outcome = 'CREATED'
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM analytics_snapshots newer
                      WHERE newer.store_id = snapshot.store_id
                        AND newer.snapshot_type = snapshot.snapshot_type
                        AND newer.period_start = snapshot.period_start
                        AND newer.period_end = snapshot.period_end
                        AND newer.revision > snapshot.revision
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM llm_analysis_jobs generation_job
                      WHERE generation_job.snapshot_id = snapshot.id
                        AND generation_job.generation_revision = 1
                  )
                ORDER BY snapshot.created_at, snapshot.id
                LIMIT ?
                """,
                this::mapTarget,
                limit
        );
    }

    private SnapshotTarget mapTarget(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new SnapshotTarget(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("store_id", UUID.class),
                resultSet.getInt("revision"),
                resultSet.getString("quality_status"),
                resultSet.getString("facts_hash"),
                resultSet.getTimestamp("created_at").toInstant()
        );
    }

    public record SnapshotTarget(
            UUID snapshotId,
            UUID storeId,
            int snapshotRevision,
            String qualityStatus,
            String factsHash,
            Instant createdAt
    ) {

        public SnapshotTarget {
            requireNonNull(snapshotId, "snapshotId");
            requireNonNull(storeId, "storeId");
            require(snapshotRevision > 0, "snapshotRevision must be positive");
            require("READY".equals(qualityStatus) || "PARTIAL".equals(qualityStatus),
                    "qualityStatus must be READY or PARTIAL");
            require(factsHash != null && factsHash.matches("[a-f0-9]{64}"),
                    "factsHash must be a lowercase SHA-256");
            requireNonNull(createdAt, "createdAt");
        }
    }
}
