package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStore;
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
public class LlmValidationClaimStore {

    private final JdbcTemplate jdbcTemplate;
    private final LlmAnalysisJobStore jobStore;

    public LlmValidationClaimStore(
            JdbcTemplate jdbcTemplate,
            LlmAnalysisJobStore jobStore
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobStore = jobStore;
    }

    @Transactional
    public Optional<LlmAnalysisJob> claimNext(
            String owner,
            Duration leaseDuration,
            Instant now
    ) {
        String leaseOwner = requireText(owner, "owner");
        require(leaseOwner.length() <= 100, "owner must not exceed 100 characters");
        Duration lease = requireNonNull(leaseDuration, "leaseDuration");
        require(!lease.isZero() && !lease.isNegative(),
                "leaseDuration must be positive");
        Instant timestamp = requireNonNull(now, "now");
        List<ClaimTarget> candidates = jdbcTemplate.query(
                """
                SELECT job.id, job.deadline_at
                FROM llm_analysis_jobs job
                WHERE job.status = 'WAITING_RETRY'
                  AND job.phase = 'VALIDATE_RESPONSE'
                  AND job.next_attempt_at <= ? AND job.deadline_at > ?
                  AND job.cancel_requested = false
                  AND EXISTS (
                      SELECT 1 FROM llm_analysis_attempts attempt
                      WHERE attempt.job_id = job.id
                        AND attempt.status = 'RESPONSE_RECEIVED'
                  )
                ORDER BY job.next_attempt_at, job.created_at
                LIMIT 1
                FOR UPDATE OF job SKIP LOCKED
                """,
                (resultSet, rowNumber) -> new ClaimTarget(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getTimestamp("deadline_at").toInstant()
                ),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp)
        );
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        ClaimTarget target = candidates.getFirst();
        Instant requestedLease = timestamp.plus(lease);
        Instant leaseUntil = requestedLease.isBefore(target.deadlineAt())
                ? requestedLease : target.deadlineAt();
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs
                SET status = 'RUNNING', attempt_count = attempt_count + 1,
                    lease_owner = ?, lease_until = ?,
                    terminal_reason_code = NULL, error_summary = NULL,
                    version = version + 1
                WHERE id = ?
                """,
                leaseOwner,
                Timestamp.from(leaseUntil),
                target.id()
        );
        return jobStore.findById(target.id());
    }

    private record ClaimTarget(UUID id, Instant deadlineAt) {
    }
}
