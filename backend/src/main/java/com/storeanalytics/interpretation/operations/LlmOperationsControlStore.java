package com.storeanalytics.interpretation.operations;

import com.storeanalytics.interpretation.generation.LlmAnalysisPlanningStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LlmOperationsControlStore {

    private final JdbcTemplate jdbcTemplate;

    public LlmOperationsControlStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RegenerationTarget lockRegenerationTarget(UUID snapshotId) {
        List<UUID> locked = jdbcTemplate.query(
                "SELECT id FROM analytics_snapshots WHERE id = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                snapshotId
        );
        if (locked.isEmpty()) {
            throw new LlmOperationsNotFoundException("LLM snapshot was not found");
        }
        List<RegenerationTarget> targets = jdbcTemplate.query(
                """
                SELECT snapshot.id, snapshot.store_id, snapshot.revision,
                       snapshot.quality_status, snapshot.facts_hash,
                       snapshot.created_at,
                       COALESCE(max(job.generation_revision), 0) + 1 AS next_revision,
                       bool_or(job.status IN ('PENDING', 'RUNNING', 'WAITING_RETRY'))
                           AS has_active,
                       EXISTS (
                           SELECT 1 FROM analytics_snapshots newer
                           WHERE newer.store_id = snapshot.store_id
                             AND newer.snapshot_type = snapshot.snapshot_type
                             AND newer.period_start = snapshot.period_start
                             AND newer.period_end = snapshot.period_end
                             AND newer.revision > snapshot.revision
                       ) AS has_newer_snapshot
                FROM analytics_snapshots snapshot
                LEFT JOIN llm_analysis_jobs job ON job.snapshot_id = snapshot.id
                WHERE snapshot.id = ?
                GROUP BY snapshot.id
                """,
                this::mapTarget,
                snapshotId
        );
        if (targets.isEmpty()) {
            throw new LlmOperationsNotFoundException("LLM snapshot was not found");
        }
        return targets.getFirst();
    }

    public JobContext jobContext(UUID jobId) {
        List<JobContext> jobs = jdbcTemplate.query(
                """
                SELECT job.id, job.snapshot_id, snapshot.store_id
                FROM llm_analysis_jobs job
                JOIN analytics_snapshots snapshot ON snapshot.id = job.snapshot_id
                WHERE job.id = ?
                """,
                (resultSet, rowNumber) -> new JobContext(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("snapshot_id", UUID.class),
                        resultSet.getObject("store_id", UUID.class)
                ),
                jobId
        );
        if (jobs.isEmpty()) {
            throw new LlmOperationsNotFoundException("LLM analysis job was not found");
        }
        return jobs.getFirst();
    }

    private RegenerationTarget mapTarget(ResultSet resultSet, int rowNumber)
            throws SQLException {
        LlmAnalysisPlanningStore.SnapshotTarget snapshot =
                new LlmAnalysisPlanningStore.SnapshotTarget(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("store_id", UUID.class),
                        resultSet.getInt("revision"),
                        resultSet.getString("quality_status"),
                        resultSet.getString("facts_hash"),
                        resultSet.getTimestamp("created_at").toInstant()
                );
        return new RegenerationTarget(
                snapshot,
                resultSet.getInt("next_revision"),
                resultSet.getBoolean("has_active"),
                resultSet.getBoolean("has_newer_snapshot")
        );
    }

    public record RegenerationTarget(
            LlmAnalysisPlanningStore.SnapshotTarget snapshot,
            int nextGenerationRevision,
            boolean hasActiveJob,
            boolean hasNewerSnapshot
    ) {
    }

    public record JobContext(UUID jobId, UUID snapshotId, UUID storeId) {
    }
}
