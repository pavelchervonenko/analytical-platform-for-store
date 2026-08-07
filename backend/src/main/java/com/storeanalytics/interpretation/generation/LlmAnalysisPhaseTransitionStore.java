package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LlmAnalysisPhaseTransitionStore {

    private final JdbcTemplate jdbcTemplate;
    private final LlmAnalysisJobStore jobStore;

    public LlmAnalysisPhaseTransitionStore(
            JdbcTemplate jdbcTemplate,
            LlmAnalysisJobStore jobStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobStore = jobStore;
    }

    @Transactional
    public LlmAnalysisJob releaseForValidation(
            UUID jobId,
            String owner,
            Instant now
    ) {
        Instant timestamp = requireNonNull(now, "now");
        LlmAnalysisJob job = requireOwnedRunning(jobId, owner);
        require(job.phase() == LlmAnalysisPhase.VALIDATE_RESPONSE,
                "LLM job must be in VALIDATE_RESPONSE phase");
        UUID attemptId = requireReceivedAttempt(job.id());
        if (job.cancelRequested()) {
            closeAttempt(attemptId, LlmAnalysisAttemptStatus.CANCELLED, null, timestamp);
            finishJob(job.id(), LlmAnalysisJobStatus.CANCELLED, null, null, timestamp);
        } else if (!job.deadlineAt().isAfter(timestamp)) {
            closeAttempt(
                    attemptId,
                    LlmAnalysisAttemptStatus.PERMANENT_FAILED,
                    LlmAnalysisJobLifecycleStore.DEADLINE_EXCEEDED,
                    timestamp
            );
            finishJob(
                    job.id(),
                    LlmAnalysisJobStatus.FAILED,
                    LlmAnalysisJobLifecycleStore.DEADLINE_EXCEEDED,
                    "LLM provider response arrived after generation deadline",
                    timestamp
            );
        } else {
            jdbcTemplate.update(
                    """
                    UPDATE llm_analysis_jobs
                    SET status = 'WAITING_RETRY', next_attempt_at = ?,
                        lease_owner = NULL, lease_until = NULL,
                        terminal_reason_code = NULL, error_summary = NULL,
                        version = version + 1
                    WHERE id = ?
                    """,
                    Timestamp.from(timestamp),
                    job.id()
            );
        }
        return requireJob(job.id());
    }

    private LlmAnalysisJob requireOwnedRunning(UUID jobId, String owner) {
        UUID id = requireNonNull(jobId, "jobId");
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM llm_analysis_jobs WHERE id = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                id
        );
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("LLM analysis job does not exist: " + id);
        }
        LlmAnalysisJob job = requireJob(id);
        String leaseOwner = requireText(owner, "owner");
        require(job.status() == LlmAnalysisJobStatus.RUNNING,
                "LLM job must be RUNNING");
        require(leaseOwner.equals(job.leaseOwner()),
                "LLM job lease is owned elsewhere");
        return job;
    }

    private UUID requireReceivedAttempt(UUID jobId) {
        List<UUID> ids = jdbcTemplate.query(
                """
                SELECT id FROM llm_analysis_attempts
                WHERE job_id = ? AND status = 'RESPONSE_RECEIVED'
                ORDER BY attempt_number DESC
                LIMIT 1
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                jobId
        );
        if (ids.isEmpty()) {
            throw new IllegalStateException(
                    "VALIDATE_RESPONSE job has no persisted provider response"
            );
        }
        return ids.getFirst();
    }

    private void closeAttempt(
            UUID attemptId,
            LlmAnalysisAttemptStatus status,
            String errorCode,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_attempts
                SET status = ?, error_code = ?, finished_at = ?
                WHERE id = ?
                """,
                status.name(),
                errorCode,
                Timestamp.from(now),
                attemptId
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
                SET status = ?, next_attempt_at = ?, lease_owner = NULL,
                    lease_until = NULL, terminal_reason_code = ?, error_summary = ?,
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

    private LlmAnalysisJob requireJob(UUID id) {
        return jobStore.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "LLM analysis job does not exist: " + id
        ));
    }
}
