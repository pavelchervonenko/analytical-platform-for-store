package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireJson;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LlmAnalysisAttemptStore {

    private static final int MAX_PROVIDER_INPUT_BYTES = 524_288;

    private static final String BY_ID_SQL =
            "SELECT * FROM llm_analysis_attempts WHERE id = ?";
    private static final String BY_ID_FOR_UPDATE_SQL =
            "SELECT * FROM llm_analysis_attempts WHERE id = ? FOR UPDATE";
    private static final String OPEN_BY_JOB_SQL = """
            SELECT * FROM llm_analysis_attempts
            WHERE job_id = ? AND status IN ('STARTED', 'RESPONSE_RECEIVED')
            ORDER BY attempt_number DESC
            LIMIT 1
            """;
    private static final String SUCCESSFUL_BY_JOB_SQL = """
            SELECT * FROM llm_analysis_attempts
            WHERE job_id = ? AND status = 'SUCCEEDED'
            ORDER BY attempt_number DESC
            LIMIT 1
            """;
    private static final String JOB_FOR_UPDATE_SQL = """
            SELECT id, status, phase, provider_code, requested_model,
                   transport_retry_count, validation_retry_count,
                   max_transport_retries, max_validation_retries,
                   (generation_parameters ->> 'maxProviderCalls')::integer
                       AS max_provider_calls,
                   lease_owner, lease_until, cancel_requested, deadline_at
            FROM llm_analysis_jobs
            WHERE id = ?
            FOR UPDATE
            """;

    private final JdbcTemplate jdbcTemplate;

    public LlmAnalysisAttemptStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public LlmAnalysisAttempt startProviderCall(
            UUID jobId,
            String owner,
            LlmAnalysisAttemptType attemptType,
            String requestHash,
            Instant now
    ) {
        return startProviderCall(
                jobId, owner, attemptType, requestHash, null, now
        );
    }

    @Transactional
    public LlmAnalysisAttempt startProviderCall(
            UUID jobId,
            String owner,
            LlmAnalysisAttemptType attemptType,
            String requestHash,
            String providerInputBody,
            Instant now
    ) {
        Instant timestamp = requireNonNull(now, "now");
        JobContext job = requireOwnedRunningJob(jobId, owner);
        require(job.leaseUntil().isAfter(timestamp), "LLM job lease has expired");
        require(job.deadlineAt().isAfter(timestamp), "LLM job deadline has passed");
        require(!job.cancelRequested(), "LLM job cancellation was requested");
        require(findOpen(job.id(), true).isEmpty(),
                "LLM job already has an unfinished provider attempt");

        int attemptNumber = nextAttemptNumber(job.id());
        LlmAnalysisAttemptType type = requireNonNull(attemptType, "attemptType");
        requireAttemptAllowed(job, type, attemptNumber);
        require(attemptNumber <= job.maxProviderCalls(),
                "provider call budget is exhausted");
        String hash = requireHash(requestHash, "requestHash");
        String inputBody = providerInputBody == null
                ? null
                : requireJson(providerInputBody, "providerInputBody");
        if (inputBody != null) {
            require(inputBody.getBytes(StandardCharsets.UTF_8).length
                            <= MAX_PROVIDER_INPUT_BYTES,
                    "providerInputBody exceeds retained input limit");
        }
        String inputHash = inputBody == null ? null : sha256(inputBody);
        UUID attemptId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO llm_analysis_attempts (
                    id, job_id, attempt_number, attempt_type, status,
                    provider_code, requested_model, request_hash,
                    provider_input_hash, provider_input_body,
                    started_at, created_at
                ) VALUES (?, ?, ?, ?, 'STARTED', ?, ?, ?, ?, ?, ?, ?)
                """,
                attemptId,
                job.id(),
                attemptNumber,
                type.name(),
                job.providerCode(),
                job.requestedModel(),
                hash,
                inputHash,
                inputBody,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp)
        );
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs
                SET phase = 'CALL_PROVIDER', version = version + 1
                WHERE id = ?
                """,
                job.id()
        );
        return requireAttempt(attemptId, false);
    }

    @Transactional
    public LlmAnalysisAttempt recordProviderResponse(
            UUID attemptId,
            String owner,
            LlmProviderResponseReceipt response,
            Instant now
    ) {
        LlmAnalysisAttempt attempt = requireAttempt(
                requireNonNull(attemptId, "attemptId"), true
        );
        JobContext job = requireOwnedRunningJob(attempt.jobId(), owner);
        LlmProviderResponseReceipt receipt = requireNonNull(response, "response");
        Instant timestamp = requireNonNull(now, "now");
        require(!timestamp.isBefore(attempt.startedAt()),
                "response timestamp must not precede attempt start");
        String responseHash = sha256(receipt.responseBody());
        if (attempt.status() == LlmAnalysisAttemptStatus.RESPONSE_RECEIVED) {
            require(sameReceipt(attempt, receipt, responseHash),
                    "provider response was already recorded with different identity");
            return attempt;
        }
        require(attempt.status() == LlmAnalysisAttemptStatus.STARTED,
                "provider response can only complete a STARTED attempt");
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_attempts
                SET status = 'RESPONSE_RECEIVED', resolved_model = ?,
                    provider_request_id = ?, response_hash = ?, response_body = ?,
                    input_tokens = ?, output_tokens = ?, cached_input_tokens = ?,
                    reasoning_tokens = ?, total_tokens = ?, cost_amount = ?,
                    cost_currency = ?, latency_ms = ?, http_status = ?,
                    response_received_at = ?
                WHERE id = ?
                """,
                receipt.resolvedModel(),
                receipt.providerRequestId(),
                responseHash,
                receipt.responseBody(),
                receipt.inputTokens(),
                receipt.outputTokens(),
                receipt.cachedInputTokens(),
                receipt.reasoningTokens(),
                receipt.totalTokens(),
                receipt.costAmount(),
                receipt.costCurrency(),
                receipt.latencyMs(),
                receipt.httpStatus(),
                Timestamp.from(timestamp),
                attempt.id()
        );
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs
                SET phase = 'VALIDATE_RESPONSE', version = version + 1
                WHERE id = ?
                """,
                job.id()
        );
        return requireAttempt(attempt.id(), false);
    }

    @Transactional(readOnly = true)
    public Optional<LlmAnalysisAttempt> findById(UUID attemptId) {
        return single(jdbcTemplate.query(
                BY_ID_SQL,
                this::mapRow,
                requireNonNull(attemptId, "attemptId")
        ));
    }

    @Transactional(readOnly = true)
    public Optional<LlmAnalysisAttempt> findOpenByJobId(UUID jobId) {
        return findOpen(requireNonNull(jobId, "jobId"), false);
    }

    @Transactional(readOnly = true)
    public Optional<LlmAnalysisAttempt> findSuccessfulByJobId(UUID jobId) {
        return single(jdbcTemplate.query(
                SUCCESSFUL_BY_JOB_SQL,
                this::mapRow,
                requireNonNull(jobId, "jobId")
        ));
    }

    private Optional<LlmAnalysisAttempt> findOpen(UUID jobId, boolean forUpdate) {
        String sql = forUpdate ? OPEN_BY_JOB_SQL + " FOR UPDATE" : OPEN_BY_JOB_SQL;
        return single(jdbcTemplate.query(sql, this::mapRow, jobId));
    }

    private int nextAttemptNumber(UUID jobId) {
        Integer value = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(MAX(attempt_number), 0) + 1
                FROM llm_analysis_attempts
                WHERE job_id = ?
                """,
                Integer.class,
                jobId
        );
        return Objects.requireNonNull(value);
    }

    private void requireAttemptAllowed(
            JobContext job,
            LlmAnalysisAttemptType type,
            int attemptNumber
    ) {
        if (type == LlmAnalysisAttemptType.INITIAL) {
            require(attemptNumber == 1, "INITIAL provider attempt must be first");
            require(job.phase() == LlmAnalysisPhase.PREPARE,
                    "INITIAL provider attempt requires PREPARE phase");
            return;
        }
        require(attemptNumber > 1, "retry provider attempt cannot be first");
        if (type == LlmAnalysisAttemptType.TRANSPORT_RETRY) {
            require(job.transportRetryCount() > 0
                            && job.transportRetryCount() <= job.maxTransportRetries(),
                    "transport retry budget was not reserved");
            require(job.phase() == LlmAnalysisPhase.CALL_PROVIDER,
                    "transport retry requires CALL_PROVIDER phase");
            return;
        }
        require(job.validationRetryCount() > 0
                        && job.validationRetryCount() <= job.maxValidationRetries(),
                "validation retry budget was not reserved");
        require(job.phase() == LlmAnalysisPhase.VALIDATE_RESPONSE,
                "validation retry requires VALIDATE_RESPONSE phase");
    }

    private JobContext requireOwnedRunningJob(UUID jobId, String owner) {
        List<JobContext> jobs = jdbcTemplate.query(
                JOB_FOR_UPDATE_SQL,
                this::mapJobContext,
                requireNonNull(jobId, "jobId")
        );
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException("LLM analysis job does not exist: " + jobId);
        }
        JobContext job = jobs.getFirst();
        String leaseOwner = requireText(owner, "owner");
        require(leaseOwner.length() <= 100, "owner must not exceed 100 characters");
        require(job.status() == LlmAnalysisJobStatus.RUNNING,
                "LLM job must be RUNNING");
        require(leaseOwner.equals(job.leaseOwner()),
                "LLM job lease is owned elsewhere");
        return job;
    }

    private LlmAnalysisAttempt requireAttempt(UUID attemptId, boolean forUpdate) {
        String sql = forUpdate ? BY_ID_FOR_UPDATE_SQL : BY_ID_SQL;
        List<LlmAnalysisAttempt> attempts = jdbcTemplate.query(
                sql,
                this::mapRow,
                attemptId
        );
        if (attempts.isEmpty()) {
            throw new IllegalArgumentException(
                    "LLM analysis attempt does not exist: " + attemptId
            );
        }
        return attempts.getFirst();
    }

    private LlmAnalysisAttempt mapRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new LlmAnalysisAttempt(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("job_id", UUID.class),
                resultSet.getInt("attempt_number"),
                LlmAnalysisAttemptType.valueOf(resultSet.getString("attempt_type")),
                LlmAnalysisAttemptStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("provider_code"),
                resultSet.getString("requested_model"),
                resultSet.getString("resolved_model"),
                resultSet.getString("provider_request_id"),
                resultSet.getString("request_hash"),
                resultSet.getString("provider_input_hash"),
                resultSet.getString("provider_input_body"),
                resultSet.getString("response_hash"),
                resultSet.getString("response_body"),
                resultSet.getString("validated_response_hash"),
                resultSet.getString("validated_response_body"),
                resultSet.getString("validation_violations"),
                resultSet.getObject("input_tokens", Integer.class),
                resultSet.getObject("output_tokens", Integer.class),
                resultSet.getObject("cached_input_tokens", Integer.class),
                resultSet.getObject("reasoning_tokens", Integer.class),
                resultSet.getObject("total_tokens", Integer.class),
                resultSet.getObject("cost_amount", BigDecimal.class),
                resultSet.getString("cost_currency"),
                resultSet.getObject("latency_ms", Long.class),
                resultSet.getObject("http_status", Integer.class),
                resultSet.getString("error_code"),
                resultSet.getString("error_summary"),
                instant(resultSet, "started_at"),
                nullableInstant(resultSet, "response_received_at"),
                nullableInstant(resultSet, "finished_at"),
                instant(resultSet, "created_at")
        );
    }

    private JobContext mapJobContext(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new JobContext(
                resultSet.getObject("id", UUID.class),
                LlmAnalysisJobStatus.valueOf(resultSet.getString("status")),
                LlmAnalysisPhase.valueOf(resultSet.getString("phase")),
                resultSet.getString("provider_code"),
                resultSet.getString("requested_model"),
                resultSet.getInt("transport_retry_count"),
                resultSet.getInt("validation_retry_count"),
                resultSet.getInt("max_transport_retries"),
                resultSet.getInt("max_validation_retries"),
                resultSet.getInt("max_provider_calls"),
                resultSet.getString("lease_owner"),
                instant(resultSet, "lease_until"),
                resultSet.getBoolean("cancel_requested"),
                instant(resultSet, "deadline_at")
        );
    }

    private boolean sameReceipt(
            LlmAnalysisAttempt attempt,
            LlmProviderResponseReceipt receipt,
            String responseHash
    ) {
        return responseHash.equals(attempt.responseHash())
                && Objects.equals(receipt.providerRequestId(), attempt.providerRequestId());
    }

    private String requireHash(String value, String field) {
        require(value != null && value.matches("[a-f0-9]{64}"),
                field + " must be a lowercase SHA-256");
        return value;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Instant instant(ResultSet resultSet, String column)
            throws SQLException {
        return resultSet.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column)
            throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static <T> Optional<T> single(List<T> values) {
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    private record JobContext(
            UUID id,
            LlmAnalysisJobStatus status,
            LlmAnalysisPhase phase,
            String providerCode,
            String requestedModel,
            int transportRetryCount,
            int validationRetryCount,
            int maxTransportRetries,
            int maxValidationRetries,
            int maxProviderCalls,
            String leaseOwner,
            Instant leaseUntil,
            boolean cancelRequested,
            Instant deadlineAt
    ) {
    }
}
