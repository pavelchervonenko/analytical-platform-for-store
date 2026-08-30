package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.generation.LlmProviderException;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Durable lease, retry and receipt persistence for the isolated active worker. */
@Component
public class WeeklyReviewAiJobStore {

    private static final int MAX_ERROR_LENGTH = 1000;
    private static final String FIND_BY_ID_SQL = """
            SELECT * FROM weekly_review_ai_jobs WHERE id = ?
            """;
    private static final String FIND_BY_SNAPSHOT_SQL = """
            SELECT *
            FROM weekly_review_ai_jobs
            WHERE snapshot_id = ?
              AND prompt_version = ?
              AND content_schema_version = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WeeklyReviewAiGenerationProperties properties;

    public WeeklyReviewAiJobStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            WeeklyReviewAiGenerationProperties properties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    public WeeklyReviewAiJob enqueue(
            UUID snapshotId,
            String providerCode,
            String requestedModel,
            int maxAttempts,
            Instant now,
            Duration deadline
    ) {
        UUID snapshot = requireNonNull(snapshotId, "snapshotId");
        Instant timestamp = requireNonNull(now, "now");
        Duration ttl = positive(deadline, "deadline");
        jdbcTemplate.update("""
                INSERT INTO weekly_review_ai_jobs (
                    id, snapshot_id, prompt_version, content_schema_version,
                    provider_code, requested_model, status, attempt_count,
                    max_attempts, next_attempt_at, deadline_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?, ?)
                ON CONFLICT (snapshot_id, prompt_version, content_schema_version)
                DO NOTHING
                """,
                UUID.randomUUID(),
                snapshot,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                requireText(providerCode, "providerCode"),
                requireText(requestedModel, "requestedModel"),
                maxAttempts,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp.plus(ttl)),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp)
        );
        return findBySnapshot(snapshot).orElseThrow(() ->
                new IllegalStateException("Weekly review AI job could not be read")
        );
    }

    @Transactional
    public int enqueueLatest(
            String providerCode,
            String requestedModel,
            int maxAttempts,
            int batchSize,
            Instant now,
            Duration deadline
    ) {
        Instant timestamp = requireNonNull(now, "now");
        Duration ttl = positive(deadline, "deadline");
        require(batchSize >= 1 && batchSize <= 100,
                "batchSize must be between 1 and 100");
        return jdbcTemplate.update("""
                INSERT INTO weekly_review_ai_jobs (
                    id, snapshot_id, prompt_version, content_schema_version,
                    provider_code, requested_model, status, attempt_count,
                    max_attempts, next_attempt_at, deadline_at, created_at, updated_at
                )
                SELECT gen_random_uuid(), candidate.id, ?, ?, ?, ?, 'PENDING', 0,
                       ?, ?, ?, ?, ?
                FROM (
                    SELECT DISTINCT ON (snapshot.store_id)
                           snapshot.id, snapshot.store_id,
                           snapshot.period_end, snapshot.revision,
                           snapshot.report_state
                    FROM weekly_review_snapshots snapshot
                    ORDER BY snapshot.store_id, snapshot.period_end DESC,
                             snapshot.revision DESC
                ) candidate
                LEFT JOIN weekly_review_ai_enrichments enrichment
                  ON enrichment.snapshot_id = candidate.id
                 AND enrichment.prompt_version = ?
                 AND enrichment.content_schema_version = ?
                LEFT JOIN weekly_review_ai_jobs job
                  ON job.snapshot_id = candidate.id
                 AND job.prompt_version = ?
                 AND job.content_schema_version = ?
                WHERE candidate.report_state IN ('READY', 'PARTIAL')
                  AND enrichment.id IS NULL
                  AND job.id IS NULL
                LIMIT ?
                ON CONFLICT (snapshot_id, prompt_version, content_schema_version)
                DO NOTHING
                """,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                requireText(providerCode, "providerCode"),
                requireText(requestedModel, "requestedModel"),
                maxAttempts,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp.plus(ttl)),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                batchSize
        );
    }

    @Transactional(readOnly = true)
    public Optional<WeeklyReviewAiJob> findBySnapshot(UUID snapshotId) {
        return single(jdbcTemplate.query(
                FIND_BY_SNAPSHOT_SQL,
                this::mapJob,
                requireNonNull(snapshotId, "snapshotId"),
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION
        ));
    }

    @Transactional(readOnly = true)
    public Optional<WeeklyReviewAiJob> findById(UUID jobId) {
        return single(jdbcTemplate.query(
                FIND_BY_ID_SQL,
                this::mapJob,
                requireNonNull(jobId, "jobId")
        ));
    }

    @Transactional
    public Optional<WeeklyReviewAiJob> claimNext(
            String owner,
            Duration leaseDuration,
            Instant now
    ) {
        String leaseOwner = requireText(owner, "owner");
        Duration lease = positive(leaseDuration, "leaseDuration");
        Instant timestamp = requireNonNull(now, "now");
        retireSuperseded(timestamp);
        recoverExpired(timestamp);
        jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs
                SET status = 'FAILED', next_attempt_at = ?,
                    last_error_code = 'JOB_DEADLINE_EXCEEDED',
                    last_error_message = 'Weekly review AI generation deadline expired',
                    lease_owner = NULL, lease_until = NULL
                WHERE status IN ('PENDING', 'RETRY_WAIT')
                  AND prompt_version = ?
                  AND content_schema_version = ?
                  AND deadline_at <= ?
                """,
                Timestamp.from(timestamp),
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                Timestamp.from(timestamp));
        List<UUID> candidates = jdbcTemplate.query("""
                SELECT id
                FROM weekly_review_ai_jobs
                WHERE status IN ('PENDING', 'RETRY_WAIT')
                  AND prompt_version = ?
                  AND content_schema_version = ?
                  AND next_attempt_at <= ?
                  AND deadline_at > ?
                  AND attempt_count < max_attempts
                ORDER BY next_attempt_at, created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp)
        );
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        UUID jobId = candidates.getFirst();
        int changed = jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs
                SET status = 'RUNNING', lease_owner = ?, lease_until = ?,
                    last_error_code = NULL, last_error_message = NULL
                WHERE id = ?
                """,
                leaseOwner,
                Timestamp.from(timestamp.plus(lease)),
                jobId
        );
        require(changed == 1, "Weekly review AI job claim was lost");
        return findById(jobId);
    }

    @Transactional
    public WeeklyReviewAiAttempt startAttempt(
            WeeklyReviewAiJob job,
            String owner,
            PreparedWeeklyReviewAiRequest prepared,
            LlmProviderPreflight preflight,
            Instant now
    ) {
        WeeklyReviewAiJob claimed = requireNonNull(job, "job");
        require(WeeklyReviewAiContract.isActive(
                        claimed.promptVersion(), claimed.contentSchemaVersion()),
                "Weekly review AI job contract is not active");
        String leaseOwner = requireText(owner, "owner");
        PreparedWeeklyReviewAiRequest request = requireNonNull(
                prepared, "prepared"
        );
        require(claimed.promptVersion().equals(request.input().promptVersion())
                        && claimed.contentSchemaVersion()
                        == request.input().contentSchemaVersion(),
                "Weekly review AI request contract does not match job");
        LlmProviderPreflight estimate = requireNonNull(preflight, "preflight");
        Instant timestamp = requireNonNull(now, "now");
        reserveDailyBudget(estimate, timestamp);
        List<Integer> numbers = jdbcTemplate.query("""
                UPDATE weekly_review_ai_jobs
                SET attempt_count = attempt_count + 1
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                  AND attempt_count < max_attempts AND deadline_at > ?
                RETURNING attempt_count
                """,
                (resultSet, rowNumber) -> resultSet.getInt(1),
                claimed.id(),
                leaseOwner,
                Timestamp.from(timestamp)
        );
        require(numbers.size() == 1, "Weekly review AI attempt cannot start");
        UUID attemptId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO weekly_review_ai_attempts (
                    id, job_id, attempt_number, status, request_hash,
                    input_hash, input_payload, estimated_cost, started_at
                ) VALUES (?, ?, ?, 'STARTED', ?, ?, CAST(? AS jsonb), ?, ?)
                """,
                attemptId,
                claimed.id(),
                numbers.getFirst(),
                request.requestHash(),
                request.inputHash(),
                request.request().inputJson(),
                estimate.estimatedMaximumCost(),
                Timestamp.from(timestamp)
        );
        return new WeeklyReviewAiAttempt(
                attemptId, claimed.id(), numbers.getFirst(), timestamp
        );
    }

    @Transactional
    public void recordProviderFailure(
            WeeklyReviewAiJob job,
            WeeklyReviewAiAttempt attempt,
            String owner,
            LlmProviderException failure,
            Duration retryDelay,
            Instant now
    ) {
        LlmProviderException problem = requireNonNull(failure, "failure");
        finishAttemptFailure(
                requireNonNull(attempt, "attempt"),
                "FAILED",
                problem.outcome().name(),
                "PROVIDER_" + problem.failureCode(),
                problem.getMessage(),
                problem.httpStatus(),
                now
        );
        transitionAfterFailure(new FailureTransition(
                job,
                owner,
                problem.isRetryable(),
                "PROVIDER_" + problem.failureCode(),
                problem.getMessage(),
                List.of(),
                problem.retryAfter() == null ? retryDelay : problem.retryAfter(),
                now
        ));
    }

    @Transactional
    public void recordValidationFailure(
            WeeklyReviewAiJob job,
            WeeklyReviewAiAttempt attempt,
            String owner,
            LlmProviderResponseReceipt response,
            WeeklyReviewAiValidationResult validation,
            Duration retryDelay,
            Instant now
    ) {
        List<String> codes = validation.violations().stream()
                .map(LlmValidationViolation::code)
                .distinct()
                .limit(20)
                .toList();
        finishAttemptResponse(
                attempt,
                "REJECTED",
                response,
                validation,
                "VALIDATION_REJECTED",
                "Provider response failed weekly review semantic validation",
                now
        );
        transitionAfterFailure(new FailureTransition(
                job,
                owner,
                true,
                "VALIDATION_REJECTED",
                "Provider response failed weekly review semantic validation",
                codes,
                retryDelay,
                now
        ));
    }

    @Transactional
    public void recordSuccessfulAttempt(
            WeeklyReviewAiJob job,
            WeeklyReviewAiAttempt attempt,
            String owner,
            LlmProviderResponseReceipt response,
            WeeklyReviewAiValidationResult validation,
            Instant now
    ) {
        finishAttemptResponse(
                attempt,
                "SUCCEEDED",
                response,
                validation,
                null,
                null,
                now
        );
        int changed = jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs
                SET status = 'SUCCEEDED', next_attempt_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    last_error_code = NULL, last_error_message = NULL,
                    last_validation_codes = '[]'::jsonb
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                """,
                Timestamp.from(requireNonNull(now, "now")),
                requireNonNull(job, "job").id(),
                requireText(owner, "owner")
        );
        require(changed == 1, "Weekly review AI success transition was lost");
    }

    @Transactional
    public void failClaimed(
            WeeklyReviewAiJob job,
            String owner,
            String errorCode,
            String errorMessage,
            Instant now
    ) {
        int changed = jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs
                SET status = 'FAILED', next_attempt_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    last_error_code = ?, last_error_message = ?
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                """,
                Timestamp.from(requireNonNull(now, "now")),
                requireText(errorCode, "errorCode"),
                safe(errorMessage),
                requireNonNull(job, "job").id(),
                requireText(owner, "owner")
        );
        require(changed == 1, "Weekly review AI failure transition was lost");
    }

    @Transactional
    public boolean heartbeat(
            UUID jobId,
            String owner,
            Duration leaseDuration,
            Instant now
    ) {
        return jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs
                SET lease_until = ?
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                """,
                Timestamp.from(requireNonNull(now, "now").plus(
                        positive(leaseDuration, "leaseDuration")
                )),
                requireNonNull(jobId, "jobId"),
                requireText(owner, "owner")
        ) == 1;
    }

    @Transactional(readOnly = true)
    public BigDecimal actualCostSince(Instant since) {
        BigDecimal value = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(actual_cost), 0)
                FROM weekly_review_ai_attempts
                WHERE finished_at >= ? AND cost_currency = 'RUB'
                """,
                BigDecimal.class,
                Timestamp.from(requireNonNull(since, "since"))
        );
        return value == null ? BigDecimal.ZERO : value;
    }

    @Transactional(readOnly = true)
    public long countByStatus(WeeklyReviewAiJobStatus status) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM weekly_review_ai_jobs
                WHERE status = ?
                  AND prompt_version = ?
                  AND content_schema_version = ?
                """,
                Long.class,
                requireNonNull(status, "status").name(),
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION
        );
        return value == null ? 0L : value;
    }

    @Transactional(readOnly = true)
    public long countExpiredLeases(Instant now) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM weekly_review_ai_jobs
                WHERE status = 'RUNNING'
                  AND prompt_version = ?
                  AND content_schema_version = ?
                  AND lease_until < ?
                """,
                Long.class,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                Timestamp.from(requireNonNull(now, "now")));
        return value == null ? 0L : value;
    }

    @Transactional(readOnly = true)
    public long countDelayed(Instant createdBefore) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM weekly_review_ai_jobs
                WHERE status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
                  AND prompt_version = ?
                  AND content_schema_version = ?
                  AND created_at < ?
                """,
                Long.class,
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                Timestamp.from(requireNonNull(
                        createdBefore, "createdBefore"
                )));
        return value == null ? 0L : value;
    }

    private void reserveDailyBudget(
            LlmProviderPreflight preflight,
            Instant now
    ) {
        if (!"RUB".equals(preflight.costCurrency())) {
            throw new WeeklyReviewAiBudgetException(
                    "CURRENCY_UNSUPPORTED",
                    "Weekly review AI provider currency is unsupported"
            );
        }
        jdbcTemplate.execute(
                "LOCK TABLE weekly_review_ai_attempts IN SHARE ROW EXCLUSIVE MODE"
        );
        Instant dayStart = now.atZone(ZoneOffset.UTC).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = dayStart.plus(Duration.ofDays(1));
        BigDecimal reserved = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(CASE
                    WHEN actual_cost IS NOT NULL AND cost_currency = 'RUB'
                        THEN actual_cost
                    WHEN status = 'STARTED'
                        THEN COALESCE(estimated_cost, 0)
                    WHEN provider_outcome IN ('UNKNOWN', 'RESPONSE_RECEIVED')
                        THEN COALESCE(estimated_cost, 0)
                    ELSE 0
                END), 0)
                FROM weekly_review_ai_attempts
                WHERE started_at >= ? AND started_at < ?
                """,
                BigDecimal.class,
                Timestamp.from(dayStart),
                Timestamp.from(dayEnd));
        BigDecimal projected = (reserved == null ? BigDecimal.ZERO : reserved)
                .add(preflight.estimatedMaximumCost());
        if (projected.compareTo(properties.dailyCostLimitRub()) > 0) {
            throw new WeeklyReviewAiBudgetException(
                    "DAILY_BUDGET_EXCEEDED",
                    "Weekly review AI request exceeds daily budget"
            );
        }
    }

    private void retireSuperseded(Instant now) {
        jdbcTemplate.update("""
                UPDATE weekly_review_ai_attempts attempt
                SET status = 'FAILED',
                    error_code = 'JOB_CONTRACT_SUPERSEDED',
                    error_message = 'Weekly review AI contract superseded',
                    provider_outcome = 'UNKNOWN',
                    finished_at = ?
                FROM weekly_review_ai_jobs job
                WHERE attempt.job_id = job.id
                  AND attempt.status = 'STARTED'
                  AND attempt.finished_at IS NULL
                  AND job.status = 'RUNNING'
                  AND (job.prompt_version <> ?
                       OR job.content_schema_version <> ?)
                  AND job.lease_until < ?
                """,
                Timestamp.from(now),
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                Timestamp.from(now)
        );
        jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs
                SET status = 'FAILED', next_attempt_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    last_error_code = 'JOB_CONTRACT_SUPERSEDED',
                    last_error_message =
                        'Weekly review AI contract superseded'
                WHERE (prompt_version <> ?
                       OR content_schema_version <> ?)
                  AND (
                      (status IN ('PENDING', 'RETRY_WAIT')
                       AND deadline_at <= ?)
                      OR (status = 'RUNNING' AND lease_until < ?)
                  )
                """,
                Timestamp.from(now),
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }

    private void recoverExpired(Instant now) {
        jdbcTemplate.update("""
                UPDATE weekly_review_ai_attempts attempt
                SET status = 'FAILED', error_code = 'LEASE_EXPIRED',
                    error_message = 'Weekly review AI worker lease expired',
                    provider_outcome = 'UNKNOWN',
                    finished_at = ?
                FROM weekly_review_ai_jobs job
                WHERE attempt.job_id = job.id
                  AND attempt.status = 'STARTED'
                  AND attempt.finished_at IS NULL
                  AND job.status = 'RUNNING'
                  AND job.prompt_version = ?
                  AND job.content_schema_version = ?
                  AND job.lease_until < ?
                """,
                Timestamp.from(now),
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                Timestamp.from(now)
        );
        jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs
                SET status = CASE
                        WHEN attempt_count >= max_attempts OR deadline_at <= ?
                            THEN 'FAILED'
                        ELSE 'RETRY_WAIT'
                    END,
                    next_attempt_at = ?, lease_owner = NULL, lease_until = NULL,
                    last_error_code = 'LEASE_EXPIRED',
                    last_error_message = 'Weekly review AI worker lease expired'
                WHERE status = 'RUNNING'
                  AND prompt_version = ?
                  AND content_schema_version = ?
                  AND lease_until < ?
                """,
                Timestamp.from(now),
                Timestamp.from(now),
                WeeklyReviewAiContract.PROMPT_VERSION,
                WeeklyReviewAiContract.CONTENT_SCHEMA_VERSION,
                Timestamp.from(now)
        );
    }

    private void transitionAfterFailure(FailureTransition transition) {
        FailureTransition value = requireNonNull(transition, "transition");
        WeeklyReviewAiJob claimed = requireNonNull(value.job(), "job");
        Instant timestamp = requireNonNull(value.now(), "now");
        Duration delay = positive(value.retryDelay(), "retryDelay");
        Instant retryAt = timestamp.plus(delay);
        boolean retry = value.retryable()
                && claimed.attemptCount() + 1 < claimed.maxAttempts()
                && retryAt.isBefore(claimed.deadlineAt());
        int changed = jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs
                SET status = ?, next_attempt_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    last_error_code = ?, last_error_message = ?,
                    last_validation_codes = CAST(? AS jsonb)
                WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                """,
                retry ? "RETRY_WAIT" : "FAILED",
                Timestamp.from(retry ? retryAt : timestamp),
                requireText(value.errorCode(), "errorCode"),
                safe(value.errorMessage()),
                json(value.validationCodes()),
                claimed.id(),
                requireText(value.owner(), "owner")
        );
        require(changed == 1, "Weekly review AI retry transition was lost");
    }

    private void finishAttemptFailure(
            WeeklyReviewAiAttempt attempt,
            String status,
            String providerOutcome,
            String errorCode,
            String errorMessage,
            Integer httpStatus,
            Instant now
    ) {
        int changed = jdbcTemplate.update("""
                UPDATE weekly_review_ai_attempts
                SET status = ?, provider_outcome = ?, error_code = ?, error_message = ?,
                    http_status = ?, finished_at = ?
                WHERE id = ? AND status = 'STARTED' AND finished_at IS NULL
                """,
                status,
                requireText(providerOutcome, "providerOutcome"),
                errorCode,
                safe(errorMessage),
                httpStatus,
                Timestamp.from(requireNonNull(now, "now")),
                attempt.id()
        );
        require(changed == 1, "Weekly review AI attempt failure was lost");
    }

    private void finishAttemptResponse(
            WeeklyReviewAiAttempt attempt,
            String status,
            LlmProviderResponseReceipt response,
            WeeklyReviewAiValidationResult validation,
            String errorCode,
            String errorMessage,
            Instant now
    ) {
        String body = requireNonNull(response, "response").responseBody();
        int changed = jdbcTemplate.update("""
                UPDATE weekly_review_ai_attempts
                SET status = ?, response_payload = ?, response_hash = ?,
                    provider_outcome = 'RESPONSE_RECEIVED',
                    validation_outcome = ?, validation_violations = CAST(? AS jsonb),
                    provider_request_id = ?, resolved_model = ?,
                    actual_cost = ?, cost_currency = ?, input_tokens = ?,
                    output_tokens = ?, total_tokens = ?, latency_ms = ?,
                    http_status = ?, error_code = ?, error_message = ?,
                    finished_at = ?
                WHERE id = ? AND status = 'STARTED' AND finished_at IS NULL
                """,
                status,
                body,
                sha256(body),
                requireNonNull(validation, "validation").outcome().name(),
                json(validation.violations()),
                response.providerRequestId(),
                response.resolvedModel(),
                response.costAmount(),
                response.costCurrency(),
                response.inputTokens(),
                response.outputTokens(),
                response.totalTokens(),
                response.latencyMs(),
                response.httpStatus(),
                errorCode,
                safe(errorMessage),
                Timestamp.from(requireNonNull(now, "now")),
                requireNonNull(attempt, "attempt").id()
        );
        require(changed == 1, "Weekly review AI response persistence was lost");
    }

    private record FailureTransition(
            WeeklyReviewAiJob job,
            String owner,
            boolean retryable,
            String errorCode,
            String errorMessage,
            List<String> validationCodes,
            Duration retryDelay,
            Instant now
    ) {
    }

    private WeeklyReviewAiJob mapJob(ResultSet row, int rowNumber)
            throws SQLException {
        return new WeeklyReviewAiJob(
                row.getObject("id", UUID.class),
                row.getObject("snapshot_id", UUID.class),
                row.getString("prompt_version"),
                row.getInt("content_schema_version"),
                row.getString("provider_code"),
                row.getString("requested_model"),
                WeeklyReviewAiJobStatus.valueOf(row.getString("status")),
                row.getInt("attempt_count"),
                row.getInt("max_attempts"),
                instant(row, "next_attempt_at"),
                instant(row, "deadline_at"),
                row.getString("lease_owner"),
                instant(row, "lease_until"),
                row.getString("last_error_code"),
                row.getString("last_error_message"),
                stringList(row.getString("last_validation_codes")),
                instant(row, "created_at"),
                instant(row, "updated_at")
        );
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private List<String> stringList(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> values = new ArrayList<>();
            root.forEach(value -> values.add(value.asText()));
            return List.copyOf(values);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Weekly review AI validation codes are unreadable",
                    exception
            );
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Weekly review AI diagnostics cannot be encoded",
                    exception
            );
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)
                    )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String safe(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.substring(0, Math.min(normalized.length(), MAX_ERROR_LENGTH));
    }

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(),
                field + " must be positive");
        return duration;
    }

    private <T> Optional<T> single(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }
}
