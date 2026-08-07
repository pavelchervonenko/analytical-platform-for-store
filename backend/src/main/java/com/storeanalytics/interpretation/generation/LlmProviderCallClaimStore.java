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
public class LlmProviderCallClaimStore {

    private final JdbcTemplate jdbcTemplate;
    private final LlmAnalysisJobStore jobStore;

    public LlmProviderCallClaimStore(
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
        Duration lease = positive(leaseDuration, "leaseDuration");
        Instant timestamp = requireNonNull(now, "now");
        List<ClaimTarget> candidates = jdbcTemplate.query(
                """
                SELECT id, deadline_at FROM llm_analysis_jobs
                WHERE status IN ('PENDING', 'WAITING_RETRY')
                  AND (
                      phase IN ('PREPARE', 'CALL_PROVIDER')
                      OR (
                          phase = 'VALIDATE_RESPONSE'
                          AND NOT EXISTS (
                              SELECT 1 FROM llm_analysis_attempts attempt
                              WHERE attempt.job_id = llm_analysis_jobs.id
                                AND attempt.status IN ('STARTED', 'RESPONSE_RECEIVED')
                          )
                      )
                  )
                  AND next_attempt_at <= ? AND deadline_at > ?
                  AND cancel_requested = false
                ORDER BY next_attempt_at, created_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
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
                    started_at = COALESCE(started_at, ?),
                    terminal_reason_code = NULL, error_summary = NULL,
                    version = version + 1
                WHERE id = ?
                """,
                leaseOwner,
                Timestamp.from(leaseUntil),
                Timestamp.from(timestamp),
                target.id()
        );
        return jobStore.findById(target.id());
    }

    private Duration positive(Duration value, String field) {
        Duration duration = requireNonNull(value, field);
        require(!duration.isZero() && !duration.isNegative(), field + " must be positive");
        return duration;
    }

    private record ClaimTarget(UUID id, Instant deadlineAt) {
    }
}
