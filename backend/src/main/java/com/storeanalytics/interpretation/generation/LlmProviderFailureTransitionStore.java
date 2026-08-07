package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically closes a failed provider attempt and either schedules one bounded
 * transport retry or terminates the job. Runtime exceptions that do not implement
 * {@link LlmProviderException} deliberately bypass this store and are recovered by
 * the expired-lease path as unknown worker failures.
 */
@Component
public class LlmProviderFailureTransitionStore {

    static final String PROVIDER_FAILURE_PREFIX = "LLM_PROVIDER_";

    private final JdbcTemplate jdbcTemplate;
    private final LlmAnalysisJobStore jobStore;

    public LlmProviderFailureTransitionStore(
            JdbcTemplate jdbcTemplate,
            LlmAnalysisJobStore jobStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobStore = jobStore;
    }

    @Transactional
    public LlmAnalysisJob recordFailure(
            UUID jobId,
            UUID attemptId,
            String owner,
            LlmProviderException failure,
            Duration fallbackRetryDelay,
            Instant now
    ) {
        Instant timestamp = requireNonNull(now, "now");
        JobContext job = requireOwnedRunningJob(jobId, owner);
        AttemptContext attempt = requireStartedAttempt(attemptId, job.id());
        LlmProviderException providerFailure = requireNonNull(failure, "failure");
        Duration fallback = positive(fallbackRetryDelay, "fallbackRetryDelay");
        String providerErrorCode = providerErrorCode(providerFailure.failureCode());
        LlmAnalysisAttemptStatus attemptStatus = attemptStatus(providerFailure);

        if (job.cancelRequested()) {
            closeAttempt(
                    attempt.id(), LlmAnalysisAttemptStatus.CANCELLED,
                    providerErrorCode, providerFailure, timestamp
            );
            finishJob(
                    job.id(), LlmAnalysisJobStatus.CANCELLED,
                    null, null, timestamp
            );
            return requireJob(job.id());
        }

        if (!job.deadlineAt().isAfter(timestamp)) {
            closeAttempt(
                    attempt.id(), attemptStatus,
                    providerErrorCode, providerFailure, timestamp
            );
            finishJob(
                    job.id(), LlmAnalysisJobStatus.FAILED,
                    LlmAnalysisJobLifecycleStore.DEADLINE_EXCEEDED,
                    "LLM provider call failed at or after the generation deadline",
                    timestamp
            );
            return requireJob(job.id());
        }

        if (!providerFailure.isRetryable()) {
            closeAttempt(
                    attempt.id(), LlmAnalysisAttemptStatus.PERMANENT_FAILED,
                    providerErrorCode, providerFailure, timestamp
            );
            finishJob(
                    job.id(), LlmAnalysisJobStatus.FAILED,
                    providerErrorCode, providerFailure.getMessage(), timestamp
            );
            return requireJob(job.id());
        }

        Instant retryAt = timestamp.plus(retryDelay(providerFailure, fallback));
        boolean retryBudgetAvailable = job.transportRetryCount()
                < job.maxTransportRetries()
                && attempt.attemptNumber() < job.maxProviderCalls();
        if (retryBudgetAvailable && retryAt.isBefore(job.deadlineAt())) {
            closeAttempt(
                    attempt.id(), attemptStatus,
                    providerErrorCode, providerFailure, timestamp
            );
            scheduleRetry(job.id(), retryAt, providerFailure.getMessage());
            return requireJob(job.id());
        }

        closeAttempt(
                attempt.id(), attemptStatus,
                providerErrorCode, providerFailure, timestamp
        );
        String terminalReason = retryAt.isBefore(job.deadlineAt())
                ? LlmAnalysisJobLifecycleStore.TRANSPORT_RETRIES_EXHAUSTED
                : LlmAnalysisJobLifecycleStore.DEADLINE_EXCEEDED;
        String summary = terminalReason.equals(
                LlmAnalysisJobLifecycleStore.TRANSPORT_RETRIES_EXHAUSTED
        )
                ? "LLM provider failure exhausted transport retry budget"
                : "LLM provider retry would exceed the generation deadline";
        finishJob(
                job.id(), LlmAnalysisJobStatus.FAILED,
                terminalReason, summary, timestamp
        );
        return requireJob(job.id());
    }

    private JobContext requireOwnedRunningJob(UUID jobId, String owner) {
        List<JobContext> jobs = jdbcTemplate.query(
                """
                SELECT id, status, transport_retry_count, max_transport_retries,
                       (generation_parameters ->> 'maxProviderCalls')::integer
                           AS max_provider_calls,
                       lease_owner, cancel_requested, deadline_at
                FROM llm_analysis_jobs
                WHERE id = ?
                FOR UPDATE
                """,
                this::mapJob,
                requireNonNull(jobId, "jobId")
        );
        if (jobs.isEmpty()) {
            throw new IllegalArgumentException(
                    "LLM analysis job does not exist: " + jobId
            );
        }
        JobContext job = jobs.getFirst();
        String leaseOwner = requireText(owner, "owner");
        require(job.status() == LlmAnalysisJobStatus.RUNNING,
                "LLM job must be RUNNING");
        require(leaseOwner.equals(job.leaseOwner()),
                "LLM job lease is owned elsewhere");
        return job;
    }

    private AttemptContext requireStartedAttempt(UUID attemptId, UUID jobId) {
        List<AttemptContext> attempts = jdbcTemplate.query(
                """
                SELECT id, job_id, attempt_number, status
                FROM llm_analysis_attempts
                WHERE id = ?
                FOR UPDATE
                """,
                this::mapAttempt,
                requireNonNull(attemptId, "attemptId")
        );
        if (attempts.isEmpty()) {
            throw new IllegalArgumentException(
                    "LLM analysis attempt does not exist: " + attemptId
            );
        }
        AttemptContext attempt = attempts.getFirst();
        require(attempt.jobId().equals(jobId),
                "LLM attempt belongs to another job");
        require(attempt.status() == LlmAnalysisAttemptStatus.STARTED,
                "provider failure can only close a STARTED attempt");
        return attempt;
    }

    private void closeAttempt(
            UUID attemptId,
            LlmAnalysisAttemptStatus status,
            String errorCode,
            LlmProviderException failure,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_attempts
                SET status = ?, http_status = ?, error_code = ?,
                    error_summary = ?, finished_at = ?
                WHERE id = ?
                """,
                status.name(),
                failure.httpStatus(),
                errorCode,
                failure.getMessage(),
                Timestamp.from(now),
                attemptId
        );
    }

    private void scheduleRetry(UUID jobId, Instant retryAt, String summary) {
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs
                SET status = 'WAITING_RETRY', phase = 'CALL_PROVIDER',
                    next_attempt_at = ?, transport_retry_count = transport_retry_count + 1,
                    lease_owner = NULL, lease_until = NULL,
                    terminal_reason_code = NULL, error_summary = ?,
                    version = version + 1
                WHERE id = ?
                """,
                Timestamp.from(retryAt),
                summary,
                jobId
        );
    }

    private void finishJob(
            UUID jobId,
            LlmAnalysisJobStatus status,
            String reason,
            String summary,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs
                SET status = ?, next_attempt_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    terminal_reason_code = ?, error_summary = ?,
                    finished_at = ?, version = version + 1
                WHERE id = ?
                """,
                status.name(),
                Timestamp.from(now),
                reason,
                summary,
                Timestamp.from(now),
                jobId
        );
    }

    private LlmAnalysisAttemptStatus attemptStatus(LlmProviderException failure) {
        return failure.outcome() == LlmProviderOutcome.UNKNOWN
                ? LlmAnalysisAttemptStatus.UNKNOWN_OUTCOME
                : LlmAnalysisAttemptStatus.TRANSIENT_FAILED;
    }

    private Duration retryDelay(
            LlmProviderException failure,
            Duration fallback
    ) {
        Duration providerDelay = failure.retryAfter();
        if (providerDelay == null || providerDelay.isNegative()
                || providerDelay.isZero()) {
            return fallback;
        }
        return providerDelay.compareTo(fallback) > 0 ? providerDelay : fallback;
    }

    private String providerErrorCode(String value) {
        String code = requireText(value, "failureCode");
        require(code.matches("[A-Z0-9_]{1,80}"),
                "provider failure code must be stable uppercase text");
        return PROVIDER_FAILURE_PREFIX + code;
    }

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isNegative() && !duration.isZero(),
                field + " must be positive");
        return duration;
    }

    private JobContext mapJob(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new JobContext(
                resultSet.getObject("id", UUID.class),
                LlmAnalysisJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getInt("transport_retry_count"),
                resultSet.getInt("max_transport_retries"),
                resultSet.getInt("max_provider_calls"),
                resultSet.getString("lease_owner"),
                resultSet.getBoolean("cancel_requested"),
                resultSet.getTimestamp("deadline_at").toInstant()
        );
    }

    private AttemptContext mapAttempt(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new AttemptContext(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("job_id", UUID.class),
                resultSet.getInt("attempt_number"),
                LlmAnalysisAttemptStatus.valueOf(resultSet.getString("status"))
        );
    }

    private LlmAnalysisJob requireJob(UUID id) {
        return jobStore.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "LLM analysis job does not exist: " + id
        ));
    }

    private record JobContext(
            UUID id,
            LlmAnalysisJobStatus status,
            int transportRetryCount,
            int maxTransportRetries,
            int maxProviderCalls,
            String leaseOwner,
            boolean cancelRequested,
            Instant deadlineAt
    ) {
    }

    private record AttemptContext(
            UUID id,
            UUID jobId,
            int attemptNumber,
            LlmAnalysisAttemptStatus status
    ) {
    }
}
