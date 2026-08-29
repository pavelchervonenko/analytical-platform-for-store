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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WeeklySnapshotJobStore {

    private static final int MAX_ERROR_CODE = 80;
    private static final int MAX_ERROR_SUMMARY = 500;
    private static final String LOCK_STORE_SQL = "SELECT id FROM stores WHERE id = ? FOR UPDATE";
    private static final String REQUEST_SQL = """
            SELECT * FROM analytics_snapshot_jobs
            WHERE store_id = ? AND period_start = ? AND period_end = ?
              AND source_sync_job_id = ? AND facts_schema_version = ?
              AND metrics_contract_version = ? AND calculation_version = ?
              AND quality_policy_version = ?
            """;
    private static final String ACTIVE_SQL = """
            SELECT * FROM analytics_snapshot_jobs
            WHERE store_id = ? AND period_start = ? AND period_end = ?
              AND status IN ('PENDING', 'RUNNING', 'WAITING_RETRY')
            LIMIT 1
            """;
    private static final String CLAIM_SQL = """
            SELECT id FROM analytics_snapshot_jobs
            WHERE status IN ('PENDING', 'WAITING_RETRY')
              AND next_attempt_at <= ? AND cancel_requested = false
            ORDER BY next_attempt_at, created_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """;
    private static final String BY_ID_SQL =
            "SELECT * FROM analytics_snapshot_jobs WHERE id = ?";
    private static final String BY_ID_FOR_UPDATE_SQL =
            "SELECT * FROM analytics_snapshot_jobs WHERE id = ? FOR UPDATE";

    private final JdbcTemplate jdbcTemplate;

    public WeeklySnapshotJobStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public WeeklySnapshotJob enqueue(WeeklySnapshotJobRequest request, Instant now) {
        WeeklySnapshotJobRequest value = requireNonNull(request, "request");
        Instant timestamp = requireNonNull(now, "now");
        lockStore(value.storeId());
        validateSource(value);
        validateBaseSnapshot(value);
        Optional<WeeklySnapshotJob> existing = findRequest(value);
        if (existing.isPresent()) {
            requireMatching(existing.get(), value);
            return existing.get();
        }
        if (findActive(value.storeId(), value.period()).isPresent()) {
            throw new WeeklySnapshotJobConflictException(
                    "An active weekly snapshot job already exists for this period"
            );
        }
        UUID id = UUID.randomUUID();
        Versions versions = value.versions();
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshot_jobs (
                    id, store_id, requested_by, job_type, period_start, period_end,
                    timezone, source_sync_job_id, source_data_cutoff,
                    facts_schema_version, metrics_contract_version, calculation_version,
                    quality_policy_version, base_snapshot_id, status, max_attempts,
                    next_attempt_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """,
                id, value.storeId(), value.requestedBy(), value.jobType().name(),
                value.period().start(), value.period().end(), value.timezone(),
                value.sourceSyncJobId(), Timestamp.from(value.sourceDataCutoff()),
                versions.factsSchemaVersion(), versions.metricContractVersion(),
                versions.calculationVersion(), versions.qualityPolicyVersion(),
                value.baseSnapshotId(), value.maxAttempts(), Timestamp.from(timestamp)
        );
        return requireJob(id, false);
    }

    @Transactional
    public Optional<WeeklySnapshotJob> claimNext(
            String owner,
            Duration leaseDuration,
            Instant now
    ) {
        String leaseOwner = requireText(owner, "owner");
        require(leaseOwner.length() <= 100, "owner must not exceed 100 characters");
        Duration lease = requireNonNull(leaseDuration, "leaseDuration");
        require(!lease.isZero() && !lease.isNegative(), "leaseDuration must be positive");
        Instant timestamp = requireNonNull(now, "now");
        List<UUID> claimable = jdbcTemplate.query(
                CLAIM_SQL,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                Timestamp.from(timestamp)
        );
        if (claimable.isEmpty()) {
            return Optional.empty();
        }
        UUID jobId = claimable.getFirst();
        jdbcTemplate.update(
                """
                UPDATE analytics_snapshot_jobs
                SET status = 'RUNNING', attempt_count = attempt_count + 1,
                    lease_owner = ?, lease_until = ?, started_at = COALESCE(started_at, ?),
                    error_code = NULL, error_summary = NULL, version = version + 1
                WHERE id = ?
                """,
                leaseOwner,
                Timestamp.from(timestamp.plus(lease)),
                Timestamp.from(timestamp),
                jobId
        );
        return Optional.of(requireJob(jobId, false));
    }

    @Transactional
    public WeeklySnapshotJob complete(
            UUID jobId,
            String owner,
            WeeklySnapshotWriteResult result,
            Instant now
    ) {
        WeeklySnapshotJob job = requireOwnedRunning(jobId, owner);
        WeeklySnapshotWriteResult writeResult = requireNonNull(result, "result");
        PersistedWeeklySnapshot snapshot = writeResult.snapshot();
        require(snapshot.storeId().equals(job.storeId()),
                "result snapshot belongs to another store");
        require(snapshot.query().period().equals(job.period()),
                "result snapshot belongs to another period");
        if (writeResult.outcome() == WeeklySnapshotWriteOutcome.UNCHANGED) {
            require(Objects.equals(job.baseSnapshotId(), snapshot.id()),
                    "UNCHANGED result must point to baseSnapshotId");
        }
        Instant timestamp = notBeforeStarted(
                job, requireNonNull(now, "now")
        );
        jdbcTemplate.update(
                """
                UPDATE analytics_snapshot_jobs
                SET status = 'SUCCESS', outcome = ?, result_snapshot_id = ?,
                    lease_owner = NULL, lease_until = NULL, finished_at = ?,
                    error_code = NULL, error_summary = NULL, version = version + 1
                WHERE id = ?
                """,
                writeResult.outcome().name(),
                snapshot.id(),
                Timestamp.from(timestamp),
                job.id()
        );
        return requireJob(job.id(), false);
    }

    @Transactional
    public WeeklySnapshotJob retryOrFail(
            UUID jobId,
            String owner,
            boolean retryable,
            String errorCode,
            String errorSummary,
            Instant nextAttemptAt,
            Instant now
    ) {
        WeeklySnapshotJob job = requireOwnedRunning(jobId, owner);
        Instant timestamp = notBeforeStarted(
                job, requireNonNull(now, "now")
        );
        boolean retry = retryable && job.attemptCount() < job.maxAttempts()
                && !job.cancelRequested();
        if (retry) {
            requireNonNull(nextAttemptAt, "nextAttemptAt");
            require(nextAttemptAt.isAfter(timestamp), "nextAttemptAt must be in the future");
        }
        WeeklySnapshotJobStatus status = job.cancelRequested()
                ? WeeklySnapshotJobStatus.CANCELLED
                : retry ? WeeklySnapshotJobStatus.WAITING_RETRY
                        : WeeklySnapshotJobStatus.FAILED;
        jdbcTemplate.update(
                """
                UPDATE analytics_snapshot_jobs
                SET status = ?, next_attempt_at = ?, lease_owner = NULL, lease_until = NULL,
                    error_code = ?, error_summary = ?, finished_at = ?, version = version + 1
                WHERE id = ?
                """,
                status.name(),
                Timestamp.from(retry ? nextAttemptAt : timestamp),
                status == WeeklySnapshotJobStatus.CANCELLED
                        ? null : bounded(errorCode, MAX_ERROR_CODE, "errorCode"),
                status == WeeklySnapshotJobStatus.CANCELLED
                        ? null : bounded(
                                errorSummary, MAX_ERROR_SUMMARY, "errorSummary"
                        ),
                status == WeeklySnapshotJobStatus.WAITING_RETRY
                        ? null : Timestamp.from(timestamp),
                job.id()
        );
        return requireJob(job.id(), false);
    }

    @Transactional(readOnly = true)
    public Optional<WeeklySnapshotJob> findById(UUID jobId) {
        return findByIdInternal(requireNonNull(jobId, "jobId"), false);
    }

    @Transactional(readOnly = true)
    public boolean requestExists(WeeklySnapshotJobRequest request) {
        return findRequest(requireNonNull(request, "request")).isPresent();
    }

    private Instant notBeforeStarted(WeeklySnapshotJob job, Instant candidate) {
        Instant startedAt = job.startedAt();
        return startedAt != null && candidate.isBefore(startedAt)
                ? startedAt : candidate;
    }

    private void validateSource(WeeklySnapshotJobRequest request) {
        Boolean valid = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM stores store
                    JOIN sync_jobs job ON job.connection_id = store.connection_id
                    WHERE store.id = ? AND job.id = ? AND job.status = 'SUCCESS'
                )
                """,
                Boolean.class,
                request.storeId(),
                request.sourceSyncJobId()
        );
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalArgumentException(
                    "sourceSyncJobId must be a successful sync for the store"
            );
        }
    }

    private void validateBaseSnapshot(WeeklySnapshotJobRequest request) {
        validateLatestBase(request);
        if (request.baseSnapshotId() == null) {
            return;
        }
        Boolean valid = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM analytics_snapshots
                    WHERE id = ? AND store_id = ? AND period_start = ? AND period_end = ?
                )
                """,
                Boolean.class,
                request.baseSnapshotId(), request.storeId(),
                request.period().start(), request.period().end()
        );
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalArgumentException(
                    "baseSnapshotId must belong to the same store and period"
            );
        }
    }

    private void validateLatestBase(WeeklySnapshotJobRequest request) {
        List<UUID> values = jdbcTemplate.query(
                """
                SELECT id FROM analytics_snapshots
                WHERE store_id = ? AND snapshot_type = 'WEEKLY'
                  AND period_start = ? AND period_end = ?
                ORDER BY revision DESC LIMIT 1
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                request.storeId(), request.period().start(), request.period().end()
        );
        UUID latestId = values.isEmpty() ? null : values.getFirst();
        if (request.jobType() == WeeklySnapshotJobType.INITIAL && latestId != null) {
            throw new WeeklySnapshotJobConflictException(
                    "INITIAL snapshot already exists for this period"
            );
        }
        if (request.jobType() != WeeklySnapshotJobType.INITIAL
                && latestId != null
                && !latestId.equals(request.baseSnapshotId())) {
            throw new WeeklySnapshotJobConflictException(
                    "baseSnapshotId must be the latest snapshot for this period"
            );
        }
    }

    private void requireMatching(WeeklySnapshotJob job, WeeklySnapshotJobRequest request) {
        if (job.jobType() != request.jobType()
                || !job.timezone().equals(request.timezone())
                || !Objects.equals(job.baseSnapshotId(), request.baseSnapshotId())) {
            throw new WeeklySnapshotJobConflictException(
                    "Existing idempotent snapshot job has different request metadata"
            );
        }
    }

    private WeeklySnapshotJob requireOwnedRunning(UUID jobId, String owner) {
        WeeklySnapshotJob job = requireJob(requireNonNull(jobId, "jobId"), true);
        String leaseOwner = requireText(owner, "owner");
        require(job.status() == WeeklySnapshotJobStatus.RUNNING,
                "snapshot job must be RUNNING");
        require(leaseOwner.equals(job.leaseOwner()), "snapshot job lease is owned elsewhere");
        return job;
    }

    private void lockStore(UUID storeId) {
        List<UUID> rows = jdbcTemplate.query(
                LOCK_STORE_SQL,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                storeId
        );
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Store does not exist: " + storeId);
        }
    }

    private Optional<WeeklySnapshotJob> findRequest(WeeklySnapshotJobRequest request) {
        Versions versions = request.versions();
        return single(jdbcTemplate.query(
                REQUEST_SQL,
                this::mapRow,
                request.storeId(), request.period().start(), request.period().end(),
                request.sourceSyncJobId(), versions.factsSchemaVersion(),
                versions.metricContractVersion(), versions.calculationVersion(),
                versions.qualityPolicyVersion()
        ));
    }

    private Optional<WeeklySnapshotJob> findActive(UUID storeId, StoreKpiPeriod period) {
        return single(jdbcTemplate.query(
                ACTIVE_SQL, this::mapRow, storeId, period.start(), period.end()
        ));
    }

    private WeeklySnapshotJob requireJob(UUID id, boolean forUpdate) {
        return findByIdInternal(id, forUpdate)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Weekly snapshot job does not exist: " + id
                ));
    }

    private Optional<WeeklySnapshotJob> findByIdInternal(UUID id, boolean forUpdate) {
        String sql = forUpdate ? BY_ID_FOR_UPDATE_SQL : BY_ID_SQL;
        return single(jdbcTemplate.query(sql, this::mapRow, id));
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

    private static String bounded(String value, int limit, String field) {
        String normalized = requireText(value, field).trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
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

    private static <T> Optional<T> single(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }
}
