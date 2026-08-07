package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Terminates a claimed job when a known-safe rejection occurs before an
 * external provider attempt is started.
 */
@Component
public class LlmPreflightFailureTransitionStore {

    private final JdbcTemplate jdbcTemplate;
    private final LlmAnalysisJobStore jobStore;

    public LlmPreflightFailureTransitionStore(
            JdbcTemplate jdbcTemplate,
            LlmAnalysisJobStore jobStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobStore = jobStore;
    }

    @Transactional
    public LlmAnalysisJob recordRejection(
            UUID jobId,
            String owner,
            String errorCode,
            String safeSummary,
            Instant now
    ) {
        JobContext job = requireOwnedRunningJob(jobId, owner);
        Instant timestamp = requireNonNull(now, "now");
        String code = requireText(errorCode, "errorCode");
        require(code.matches("[A-Z0-9_]{1,120}"),
                "preflight error code must be stable uppercase text");
        String summary = requireText(safeSummary, "safeSummary");

        if (job.cancelRequested()) {
            finishJob(
                    job.id(), LlmAnalysisJobStatus.CANCELLED,
                    null, null, timestamp
            );
        } else if (!job.deadlineAt().isAfter(timestamp)) {
            finishJob(
                    job.id(), LlmAnalysisJobStatus.FAILED,
                    LlmAnalysisJobLifecycleStore.DEADLINE_EXCEEDED,
                    "LLM preflight completed at or after the generation deadline",
                    timestamp
            );
        } else {
            finishJob(
                    job.id(), LlmAnalysisJobStatus.FAILED,
                    code, summary, timestamp
            );
        }
        return requireJob(job.id());
    }

    private JobContext requireOwnedRunningJob(UUID jobId, String owner) {
        List<JobContext> jobs = jdbcTemplate.query(
                """
                SELECT id, status, lease_owner, cancel_requested, deadline_at
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

    private JobContext mapJob(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new JobContext(
                resultSet.getObject("id", UUID.class),
                LlmAnalysisJobStatus.valueOf(resultSet.getString("status")),
                resultSet.getString("lease_owner"),
                resultSet.getBoolean("cancel_requested"),
                resultSet.getTimestamp("deadline_at").toInstant()
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
            String leaseOwner,
            boolean cancelRequested,
            Instant deadlineAt
    ) {
    }
}
