package com.storeanalytics.interpretation.snapshot;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.Versions;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WeeklySnapshotJobLifecycleStore {

    static final String EXPIRED_LEASE_ERROR_CODE = "WORKER_LEASE_EXPIRED";
    static final String EXPIRED_LEASE_ERROR_SUMMARY =
            "Weekly snapshot worker lease expired";

    private static final String BY_ID_FOR_UPDATE_SQL =
            "SELECT * FROM analytics_snapshot_jobs WHERE id = ? FOR UPDATE";
    private static final String EXPIRED_SQL = """
            SELECT * FROM analytics_snapshot_jobs
            WHERE status = 'RUNNING' AND lease_until < ?
            ORDER BY lease_until, created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcTemplate jdbcTemplate;

    public WeeklySnapshotJobLifecycleStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public WeeklySnapshotJob heartbeat(
            UUID jobId,
            String owner,
            Duration leaseDuration,
            Instant now
    ) {
        WeeklySnapshotJob job = ownedRunning(jobId, owner);
        Duration lease = positive(leaseDuration, "leaseDuration");
        Instant timestamp = requireNonNull(now, "now");
        require(job.leaseUntil().isAfter(timestamp),
                "snapshot job lease has already expired");
        jdbcTemplate.update(
                """
                UPDATE analytics_snapshot_jobs
                SET lease_until = ?, version = version + 1
                WHERE id = ?
                """,
                Timestamp.from(timestamp.plus(lease)),
                job.id()
        );
        return requireJob(job.id(), false);
    }

    @Transactional
    public Optional<WeeklySnapshotJob> recoverOneExpiredLease(
            Instant nextAttemptAt,
            Instant now
    ) {
        Instant timestamp = requireNonNull(now, "now");
        Instant retryAt = requireNonNull(nextAttemptAt, "nextAttemptAt");
        require(retryAt.isAfter(timestamp), "nextAttemptAt must be in the future");
        List<WeeklySnapshotJob> expired = jdbcTemplate.query(
                EXPIRED_SQL,
                this::mapRow,
                Timestamp.from(timestamp)
        );
        if (expired.isEmpty()) {
            return Optional.empty();
        }
        WeeklySnapshotJob job = expired.getFirst();
        boolean retry = !job.cancelRequested()
                && job.attemptCount() < job.maxAttempts();
        WeeklySnapshotJobStatus status = job.cancelRequested()
                ? WeeklySnapshotJobStatus.CANCELLED
                : retry ? WeeklySnapshotJobStatus.WAITING_RETRY
                        : WeeklySnapshotJobStatus.FAILED;
        jdbcTemplate.update(
                """
                UPDATE analytics_snapshot_jobs
                SET status = ?, next_attempt_at = ?, lease_owner = NULL,
                    lease_until = NULL, error_code = ?, error_summary = ?,
                    finished_at = ?, version = version + 1
                WHERE id = ?
                """,
                status.name(),
                Timestamp.from(retry ? retryAt : timestamp),
                status == WeeklySnapshotJobStatus.CANCELLED
                        ? null : EXPIRED_LEASE_ERROR_CODE,
                status == WeeklySnapshotJobStatus.CANCELLED
                        ? null : EXPIRED_LEASE_ERROR_SUMMARY,
                retry ? null : Timestamp.from(timestamp),
                job.id()
        );
        return Optional.of(requireJob(job.id(), false));
    }

    @Transactional
    public WeeklySnapshotJob requestCancellation(UUID jobId, Instant now) {
        WeeklySnapshotJob job = requireJob(requireNonNull(jobId, "jobId"), true);
        Instant timestamp = requireNonNull(now, "now");
        if (terminal(job.status()) || job.cancelRequested()) {
            return job;
        }
        if (job.status() == WeeklySnapshotJobStatus.RUNNING) {
            jdbcTemplate.update(
                    """
                    UPDATE analytics_snapshot_jobs
                    SET cancel_requested = true, version = version + 1
                    WHERE id = ?
                    """,
                    job.id()
            );
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE analytics_snapshot_jobs
                    SET status = 'CANCELLED', cancel_requested = true,
                        next_attempt_at = ?, lease_owner = NULL, lease_until = NULL,
                        error_code = NULL, error_summary = NULL, finished_at = ?,
                        version = version + 1
                    WHERE id = ?
                    """,
                    Timestamp.from(timestamp),
                    Timestamp.from(timestamp),
                    job.id()
            );
        }
        return requireJob(job.id(), false);
    }

    @Transactional(readOnly = true)
    public long countByStatus(WeeklySnapshotJobStatus status) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM analytics_snapshot_jobs WHERE status = ?",
                Long.class,
                requireNonNull(status, "status").name()
        );
        return value == null ? 0 : value;
    }

    @Transactional(readOnly = true)
    public long countExpiredLeases(Instant now) {
        Long value = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM analytics_snapshot_jobs
                WHERE status = 'RUNNING' AND lease_until < ?
                """,
                Long.class,
                Timestamp.from(requireNonNull(now, "now"))
        );
        return value == null ? 0 : value;
    }

    private WeeklySnapshotJob ownedRunning(UUID jobId, String owner) {
        WeeklySnapshotJob job = requireJob(requireNonNull(jobId, "jobId"), true);
        String leaseOwner = requireText(owner, "owner");
        require(job.status() == WeeklySnapshotJobStatus.RUNNING,
                "snapshot job must be RUNNING");
        require(leaseOwner.equals(job.leaseOwner()),
                "snapshot job lease is owned elsewhere");
        return job;
    }

    private WeeklySnapshotJob requireJob(UUID jobId, boolean forUpdate) {
        String sql = forUpdate
                ? BY_ID_FOR_UPDATE_SQL
                : "SELECT * FROM analytics_snapshot_jobs WHERE id = ?";
        List<WeeklySnapshotJob> jobs = jdbcTemplate.query(sql, this::mapRow, jobId);
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Weekly snapshot job does not exist: " + jobId
            );
        }
        return jobs.getFirst();
    }

    private WeeklySnapshotJob mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        Versions versions = new Versions(
                resultSet.getInt("facts_schema_version"),
                resultSet.getString("metrics_contract_version"),
                resultSet.getString("calculation_version"),
                resultSet.getString("quality_policy_version")
        );
        return new WeeklySnapshotJob(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("store_id", UUID.class),
                resultSet.getObject("requested_by", UUID.class),
                WeeklySnapshotJobType.valueOf(resultSet.getString("job_type")),
                new StoreKpiPeriod(
                        resultSet.getObject("period_start", LocalDate.class),
                        resultSet.getObject("period_end", LocalDate.class)
                ),
                resultSet.getString("timezone"),
                resultSet.getObject("source_sync_job_id", UUID.class),
                instant(resultSet, "source_data_cutoff"),
                versions,
                resultSet.getObject("base_snapshot_id", UUID.class),
                WeeklySnapshotJobStatus.valueOf(resultSet.getString("status")),
                enumValue(WeeklySnapshotWriteOutcome.class, resultSet.getString("outcome")),
                resultSet.getObject("result_snapshot_id", UUID.class),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("max_attempts"),
                instant(resultSet, "next_attempt_at"),
                resultSet.getString("lease_owner"),
                nullableInstant(resultSet, "lease_until"),
                resultSet.getBoolean("cancel_requested"),
                resultSet.getString("error_code"),
                resultSet.getString("error_summary"),
                nullableInstant(resultSet, "started_at"),
                nullableInstant(resultSet, "finished_at"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
    }

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(), field + " must be positive");
        return duration;
    }

    private boolean terminal(WeeklySnapshotJobStatus status) {
        return status == WeeklySnapshotJobStatus.SUCCESS
                || status == WeeklySnapshotJobStatus.FAILED
                || status == WeeklySnapshotJobStatus.CANCELLED;
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column)
            throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
