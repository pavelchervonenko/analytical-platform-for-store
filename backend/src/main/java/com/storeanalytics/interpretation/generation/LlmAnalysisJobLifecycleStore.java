package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LlmAnalysisJobLifecycleStore {

    static final String LEASE_EXPIRED = "LLM_WORKER_LEASE_EXPIRED";
    static final String DEADLINE_EXCEEDED = "LLM_GENERATION_DEADLINE_EXCEEDED";
    static final String PROVIDER_OUTCOME_UNKNOWN = "LLM_PROVIDER_OUTCOME_UNKNOWN";
    static final String TRANSPORT_RETRIES_EXHAUSTED =
            "LLM_TRANSPORT_RETRIES_EXHAUSTED";

    private final JdbcTemplate jdbcTemplate;
    private final LlmAnalysisJobStore jobStore;

    public LlmAnalysisJobLifecycleStore(
            JdbcTemplate jdbcTemplate,
            LlmAnalysisJobStore jobStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobStore = jobStore;
    }

    @Transactional
    public LlmAnalysisJob heartbeat(
            UUID jobId,
            String owner,
            Duration leaseDuration,
            Instant now
    ) {
        Instant timestamp = requireNonNull(now, "now");
        LlmAnalysisJob job = requireOwnedRunning(jobId, owner);
        require(job.leaseUntil().isAfter(timestamp), "LLM job lease has already expired");
        require(job.deadlineAt().isAfter(timestamp), "LLM job deadline has passed");
        Instant requestedLease = timestamp.plus(positive(leaseDuration, "leaseDuration"));
        Instant leaseUntil = requestedLease.isBefore(job.deadlineAt())
                ? requestedLease : job.deadlineAt();
        jdbcTemplate.update(
                "UPDATE llm_analysis_jobs SET lease_until = ?, version = version + 1 WHERE id = ?",
                Timestamp.from(leaseUntil),
                job.id()
        );
        return requireJob(job.id());
    }

    @Transactional
    public Optional<LlmAnalysisJob> expireOnePastDeadline(Instant now) {
        Instant timestamp = requireNonNull(now, "now");
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT id FROM llm_analysis_jobs
                WHERE status IN ('PENDING', 'WAITING_RETRY') AND deadline_at <= ?
                ORDER BY deadline_at, created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                Timestamp.from(timestamp)
        );
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        UUID id = ids.getFirst();
        closeOpenAttemptPastDeadline(id, timestamp);
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs
                SET status = 'SKIPPED', terminal_reason_code = ?,
                    error_summary = 'LLM generation deadline passed before claim',
                    next_attempt_at = ?, finished_at = ?, version = version + 1
                WHERE id = ?
                """,
                DEADLINE_EXCEEDED,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                id
        );
        return Optional.of(requireJob(id));
    }

    @Transactional
    public Optional<LlmAnalysisJob> recoverOneExpiredLease(
            Instant nextAttemptAt,
            Instant now
    ) {
        Instant timestamp = requireNonNull(now, "now");
        Instant retryAt = requireNonNull(nextAttemptAt, "nextAttemptAt");
        require(retryAt.isAfter(timestamp), "nextAttemptAt must be in the future");
        List<RecoveryTarget> targets = jdbcTemplate.query(
                """
                SELECT id, phase, cancel_requested, deadline_at,
                       transport_retry_count, max_transport_retries,
                       (SELECT count(*) FROM llm_analysis_attempts attempt
                        WHERE attempt.job_id = llm_analysis_jobs.id)
                            AS provider_call_count,
                       (generation_parameters ->> 'maxProviderCalls')::integer
                            AS max_provider_calls
                FROM llm_analysis_jobs
                WHERE status = 'RUNNING' AND lease_until < ?
                ORDER BY lease_until, created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new RecoveryTarget(
                        resultSet.getObject("id", UUID.class),
                        LlmAnalysisPhase.valueOf(resultSet.getString("phase")),
                        resultSet.getInt("transport_retry_count"),
                        resultSet.getInt("max_transport_retries"),
                        resultSet.getInt("provider_call_count"),
                        resultSet.getInt("max_provider_calls"),
                        resultSet.getBoolean("cancel_requested"),
                        resultSet.getTimestamp("deadline_at").toInstant()
                ),
                Timestamp.from(timestamp)
        );
        if (targets.isEmpty()) {
            return Optional.empty();
        }
        RecoveryTarget target = targets.getFirst();
        Optional<OpenAttempt> openAttempt = findOpenAttemptForUpdate(target.id());
        RecoveryDecision decision = recoveryDecision(target, openAttempt, retryAt);
        closeAttemptIfRequired(openAttempt, decision, timestamp);
        boolean retry = decision.status() == LlmAnalysisJobStatus.WAITING_RETRY;
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs SET status = ?, phase = ?, next_attempt_at = ?,
                    transport_retry_count = transport_retry_count + ?,
                    lease_owner = NULL, lease_until = NULL,
                    terminal_reason_code = ?, error_summary = ?, finished_at = ?,
                    version = version + 1
                WHERE id = ?
                """,
                decision.status().name(),
                decision.phase().name(),
                Timestamp.from(retry ? retryAt : timestamp),
                decision.transportRetryIncrement(),
                decision.reason(),
                decision.summary(),
                retry ? null : Timestamp.from(timestamp),
                target.id()
        );
        return Optional.of(requireJob(target.id()));
    }

    @Transactional
    public LlmAnalysisJob requestCancellation(UUID jobId, Instant now) {
        LlmAnalysisJob job = requireJobForUpdate(requireNonNull(jobId, "jobId"));
        Instant timestamp = requireNonNull(now, "now");
        if (terminal(job.status()) || job.cancelRequested()) {
            return job;
        }
        if (job.status() == LlmAnalysisJobStatus.RUNNING) {
            jdbcTemplate.update(
                    "UPDATE llm_analysis_jobs SET cancel_requested = true, version = version + 1 WHERE id = ?",
                    job.id()
            );
        } else {
            closeOpenAttemptForCancellation(job.id(), timestamp);
            jdbcTemplate.update(
                    """
                    UPDATE llm_analysis_jobs
                    SET status = 'CANCELLED', cancel_requested = true,
                        next_attempt_at = ?, lease_owner = NULL, lease_until = NULL,
                        terminal_reason_code = NULL, error_summary = NULL,
                        finished_at = ?, version = version + 1
                    WHERE id = ?
                    """,
                    Timestamp.from(timestamp),
                    Timestamp.from(timestamp),
                    job.id()
            );
        }
        return requireJob(job.id());
    }

    @Transactional(readOnly = true)
    public long countByStatus(LlmAnalysisJobStatus status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_analysis_jobs WHERE status = ?",
                Long.class,
                requireNonNull(status, "status").name()
        );
        return count == null ? 0 : count;
    }

    @Transactional(readOnly = true)
    public long countDeadlineExceeded() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_analysis_jobs WHERE terminal_reason_code = ?",
                Long.class,
                DEADLINE_EXCEEDED
        );
        return count == null ? 0 : count;
    }

    @Transactional(readOnly = true)
    public long countExpiredLeases(Instant now) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_analysis_jobs WHERE status = 'RUNNING' AND lease_until < ?",
                Long.class,
                Timestamp.from(requireNonNull(now, "now"))
        );
        return count == null ? 0 : count;
    }

    private RecoveryDecision recoveryDecision(
            RecoveryTarget target,
            Optional<OpenAttempt> openAttempt,
            Instant retryAt
    ) {
        if (target.cancelRequested()) {
            return new RecoveryDecision(
                    LlmAnalysisJobStatus.CANCELLED, target.phase(), null, null, 0,
                    LlmAnalysisAttemptStatus.CANCELLED
            );
        }
        if (!retryAt.isBefore(target.deadlineAt())) {
            return new RecoveryDecision(
                    LlmAnalysisJobStatus.FAILED, target.phase(), DEADLINE_EXCEEDED,
                    "LLM worker lease expired after generation deadline", 0,
                    LlmAnalysisAttemptStatus.PERMANENT_FAILED
            );
        }
        if (openAttempt.isPresent()
                && openAttempt.get().status()
                == LlmAnalysisAttemptStatus.RESPONSE_RECEIVED) {
            return new RecoveryDecision(
                    LlmAnalysisJobStatus.WAITING_RETRY,
                    LlmAnalysisPhase.VALIDATE_RESPONSE, null,
                    "Persisted provider response scheduled for validation", 0, null
            );
        }
        if (openAttempt.isPresent()) {
            boolean retry = target.transportRetryCount()
                    < target.maxTransportRetries();
            retry = retry && target.providerCallCount() < target.maxProviderCalls();
            return new RecoveryDecision(
                    retry ? LlmAnalysisJobStatus.WAITING_RETRY
                            : LlmAnalysisJobStatus.FAILED,
                    LlmAnalysisPhase.CALL_PROVIDER,
                    retry ? null : TRANSPORT_RETRIES_EXHAUSTED,
                    retry
                            ? "Unknown provider outcome scheduled for transport retry"
                            : "Unknown provider outcome exhausted transport retry budget",
                    retry ? 1 : 0,
                    LlmAnalysisAttemptStatus.UNKNOWN_OUTCOME
            );
        }
        return new RecoveryDecision(
                LlmAnalysisJobStatus.WAITING_RETRY, target.phase(), null,
                "LLM worker lease expired; job scheduled for recovery", 0, null
        );
    }

    private Optional<OpenAttempt> findOpenAttemptForUpdate(UUID jobId) {
        List<OpenAttempt> attempts = jdbcTemplate.query(
                """
                SELECT id, status FROM llm_analysis_attempts
                WHERE job_id = ? AND status IN ('STARTED', 'RESPONSE_RECEIVED')
                ORDER BY attempt_number DESC
                LIMIT 1
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new OpenAttempt(
                        resultSet.getObject("id", UUID.class),
                        LlmAnalysisAttemptStatus.valueOf(resultSet.getString("status"))
                ),
                jobId
        );
        return attempts.isEmpty() ? Optional.empty() : Optional.of(attempts.getFirst());
    }

    private void closeAttemptIfRequired(
            Optional<OpenAttempt> openAttempt,
            RecoveryDecision decision,
            Instant now
    ) {
        if (openAttempt.isEmpty() || decision.attemptStatus() == null) {
            return;
        }
        OpenAttempt attempt = openAttempt.get();
        LlmAnalysisAttemptStatus status = decision.attemptStatus();
        if (attempt.status() == LlmAnalysisAttemptStatus.STARTED
                && status != LlmAnalysisAttemptStatus.UNKNOWN_OUTCOME) {
            status = LlmAnalysisAttemptStatus.UNKNOWN_OUTCOME;
        }
        String errorCode = status == LlmAnalysisAttemptStatus.UNKNOWN_OUTCOME
                ? PROVIDER_OUTCOME_UNKNOWN : decision.reason();
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_attempts
                SET status = ?, error_code = ?, error_summary = ?, finished_at = ?
                WHERE id = ?
                """,
                status.name(),
                errorCode,
                decision.summary(),
                Timestamp.from(now),
                attempt.id()
        );
    }

    private void closeOpenAttemptPastDeadline(UUID jobId, Instant now) {
        findOpenAttemptForUpdate(jobId).ifPresent(attempt -> closeAttemptIfRequired(
                Optional.of(attempt),
                new RecoveryDecision(
                        LlmAnalysisJobStatus.SKIPPED, LlmAnalysisPhase.PREPARE,
                        DEADLINE_EXCEEDED,
                        "LLM generation deadline passed before claim", 0,
                        LlmAnalysisAttemptStatus.PERMANENT_FAILED
                ),
                now
        ));
    }

    private void closeOpenAttemptForCancellation(UUID jobId, Instant now) {
        findOpenAttemptForUpdate(jobId).ifPresent(attempt -> closeAttemptIfRequired(
                Optional.of(attempt),
                new RecoveryDecision(
                        LlmAnalysisJobStatus.CANCELLED, LlmAnalysisPhase.PREPARE,
                        null, null, 0, LlmAnalysisAttemptStatus.CANCELLED
                ),
                now
        ));
    }

    private LlmAnalysisJob requireOwnedRunning(UUID jobId, String owner) {
        LlmAnalysisJob job = requireJobForUpdate(requireNonNull(jobId, "jobId"));
        String leaseOwner = requireText(owner, "owner");
        require(job.status() == LlmAnalysisJobStatus.RUNNING, "LLM job must be RUNNING");
        require(leaseOwner.equals(job.leaseOwner()), "LLM job lease is owned elsewhere");
        return job;
    }

    private LlmAnalysisJob requireJobForUpdate(UUID id) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM llm_analysis_jobs WHERE id = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                id
        );
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("LLM analysis job does not exist: " + id);
        }
        return requireJob(id);
    }

    private LlmAnalysisJob requireJob(UUID id) {
        return jobStore.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "LLM analysis job does not exist: " + id
        ));
    }

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(), field + " must be positive");
        return duration;
    }

    private boolean terminal(LlmAnalysisJobStatus status) {
        return status == LlmAnalysisJobStatus.SUCCESS
                || status == LlmAnalysisJobStatus.VALIDATION_FAILED
                || status == LlmAnalysisJobStatus.FAILED
                || status == LlmAnalysisJobStatus.SKIPPED
                || status == LlmAnalysisJobStatus.CANCELLED;
    }

    private record RecoveryTarget(
            UUID id,
            LlmAnalysisPhase phase,
            int transportRetryCount,
            int maxTransportRetries,
            int providerCallCount,
            int maxProviderCalls,
            boolean cancelRequested,
            Instant deadlineAt
    ) {
    }

    private record OpenAttempt(
            UUID id,
            LlmAnalysisAttemptStatus status
    ) {
    }

    private record RecoveryDecision(
            LlmAnalysisJobStatus status,
            LlmAnalysisPhase phase,
            String reason,
            String summary,
            int transportRetryIncrement,
            LlmAnalysisAttemptStatus attemptStatus
    ) {
    }
}
