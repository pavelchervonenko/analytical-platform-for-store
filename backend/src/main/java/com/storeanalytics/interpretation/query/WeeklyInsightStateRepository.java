package com.storeanalytics.interpretation.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WeeklyInsightStateRepository {

    private final JdbcTemplate jdbcTemplate;

    public WeeklyInsightStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> storeTimezone(UUID storeId) {
        return single(jdbcTemplate.query(
                "SELECT timezone FROM stores WHERE id = ? AND is_active = true",
                (resultSet, rowNumber) -> resultSet.getString("timezone"),
                storeId
        ));
    }

    public Optional<ProcessState> latestSnapshotJob(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        return single(jdbcTemplate.query("""
                SELECT status, created_at, updated_at
                FROM analytics_snapshot_jobs
                WHERE store_id = ? AND period_start = ? AND period_end = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, this::mapState, storeId, periodStart, periodEnd));
    }

    public Optional<ProcessState> latestAnalysisJob(UUID snapshotId) {
        return single(jdbcTemplate.query("""
                SELECT status, created_at, updated_at
                FROM llm_analysis_jobs
                WHERE snapshot_id = ?
                ORDER BY generation_revision DESC, created_at DESC, id DESC
                LIMIT 1
                """, this::mapState, snapshotId));
    }

    private ProcessState mapState(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new ProcessState(
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant()
        );
    }

    private static <T> Optional<T> single(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    public record ProcessState(
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {

        public boolean active() {
            return status.equals("PENDING")
                    || status.equals("RUNNING")
                    || status.equals("WAITING_RETRY");
        }

        public boolean failed() {
            return status.equals("FAILED")
                    || status.equals("VALIDATION_FAILED")
                    || status.equals("SKIPPED")
                    || status.equals("CANCELLED");
        }
    }
}
