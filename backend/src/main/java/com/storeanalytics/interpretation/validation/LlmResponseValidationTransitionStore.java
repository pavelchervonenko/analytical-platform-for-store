package com.storeanalytics.interpretation.validation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.contract.CanonicalLlmJson;
import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import com.storeanalytics.interpretation.generation.LlmAnalysisAttemptStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisPhase;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class LlmResponseValidationTransitionStore {

    static final String STRUCTURAL_INVALID = "LLM_RESPONSE_STRUCTURAL_INVALID";
    static final String SEMANTIC_INVALID = "LLM_RESPONSE_SEMANTIC_INVALID";
    private static final String DEADLINE_EXCEEDED =
            "LLM_GENERATION_DEADLINE_EXCEEDED";

    private final JdbcTemplate jdbcTemplate;
    private final LlmAnalysisJobStore jobStore;
    private final ObjectMapper objectMapper;
    private final LlmCanonicalJsonCodec jsonCodec;

    public LlmResponseValidationTransitionStore(
            JdbcTemplate jdbcTemplate,
            LlmAnalysisJobStore jobStore,
            ObjectMapper objectMapper,
            LlmCanonicalJsonCodec jsonCodec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobStore = jobStore;
        this.objectMapper = objectMapper;
        this.jsonCodec = jsonCodec;
    }

    @Transactional
    public LlmAnalysisJob complete(
            UUID jobId,
            UUID attemptId,
            String owner,
            LlmResponseValidationResult result,
            Instant now
    ) {
        Instant timestamp = requireNonNull(now, "now");
        LlmAnalysisJob job = requireOwnedValidationJob(jobId, owner);
        UUID receivedAttemptId = requireReceivedAttempt(job.id());
        require(receivedAttemptId.equals(requireNonNull(attemptId, "attemptId")),
                "validation attempt does not match the open provider response");
        LlmResponseValidationResult outcome = requireNonNull(result, "result");
        if (job.cancelRequested()) {
            closeCancelled(receivedAttemptId, job.id(), timestamp);
        } else if (!job.deadlineAt().isAfter(timestamp)) {
            closeDeadlineExceeded(receivedAttemptId, job.id(), timestamp);
        } else if (outcome.outcome() == LlmValidationOutcome.VALID) {
            closeSuccessful(
                    receivedAttemptId,
                    job.id(),
                    outcome.canonicalContent(),
                    timestamp
            );
        } else {
            closeInvalid(receivedAttemptId, job.id(), outcome, timestamp);
        }
        return requireJob(job.id());
    }

    private LlmAnalysisJob requireOwnedValidationJob(UUID jobId, String owner) {
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
        require(job.status() == LlmAnalysisJobStatus.RUNNING,
                "LLM job must be RUNNING");
        require(job.phase() == LlmAnalysisPhase.VALIDATE_RESPONSE,
                "LLM job must be in VALIDATE_RESPONSE phase");
        require(requireText(owner, "owner").equals(job.leaseOwner()),
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

    private void closeSuccessful(
            UUID attemptId,
            UUID jobId,
            String canonicalContent,
            Instant now
    ) {
        CanonicalLlmJson validated = jsonCodec.canonicalize(
                requireText(canonicalContent, "canonicalContent")
        );
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_attempts
                SET status = 'SUCCEEDED', validation_violations = '[]'::jsonb,
                    validated_response_hash = ?, validated_response_body = ?,
                    error_code = NULL, error_summary = NULL, finished_at = ?
                WHERE id = ?
                """,
                validated.contentHash(),
                validated.canonicalJson(),
                Timestamp.from(now),
                attemptId
        );
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs
                SET status = 'WAITING_RETRY', phase = 'PUBLISH', next_attempt_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    terminal_reason_code = NULL, error_summary = NULL,
                    version = version + 1
                WHERE id = ?
                """,
                Timestamp.from(now),
                jobId
        );
    }

    private void closeInvalid(
            UUID attemptId,
            UUID jobId,
            LlmResponseValidationResult result,
            Instant now
    ) {
        boolean structural = result.outcome()
                == LlmValidationOutcome.STRUCTURAL_INVALID;
        String code = structural ? STRUCTURAL_INVALID : SEMANTIC_INVALID;
        LlmAnalysisAttemptStatus attemptStatus = structural
                ? LlmAnalysisAttemptStatus.STRUCTURAL_INVALID
                : LlmAnalysisAttemptStatus.SEMANTIC_INVALID;
        String summary = "LLM response rejected by "
                + (structural ? "structural" : "semantic")
                + " validation (" + result.violations().size() + " violation(s))";
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_attempts
                SET status = ?, validation_violations = CAST(? AS jsonb),
                    error_code = ?, error_summary = ?, finished_at = ?
                WHERE id = ?
                """,
                attemptStatus.name(),
                violationsJson(result.violations()),
                code,
                summary,
                Timestamp.from(now),
                attemptId
        );
        LlmAnalysisJob job = requireJob(jobId);
        if (job.validationRetryCount() < job.maxValidationRetries()) {
            scheduleValidationRetry(jobId, summary, now);
        } else {
            finishJob(jobId, LlmAnalysisJobStatus.VALIDATION_FAILED,
                    code, summary, now);
        }
    }

    private void scheduleValidationRetry(
            UUID jobId,
            String summary,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs
                SET status = 'WAITING_RETRY', phase = 'VALIDATE_RESPONSE',
                    validation_retry_count = validation_retry_count + 1,
                    next_attempt_at = ?, lease_owner = NULL, lease_until = NULL,
                    terminal_reason_code = NULL, error_summary = ?,
                    version = version + 1
                WHERE id = ?
                """,
                Timestamp.from(now),
                summary,
                jobId
        );
    }

    private void closeCancelled(UUID attemptId, UUID jobId, Instant now) {
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_attempts
                SET status = 'CANCELLED', finished_at = ? WHERE id = ?
                """,
                Timestamp.from(now),
                attemptId
        );
        finishJob(jobId, LlmAnalysisJobStatus.CANCELLED, null, null, now);
    }

    private void closeDeadlineExceeded(UUID attemptId, UUID jobId, Instant now) {
        String code = DEADLINE_EXCEEDED;
        String summary = "LLM response validation exceeded generation deadline";
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_attempts
                SET status = 'PERMANENT_FAILED', error_code = ?, error_summary = ?,
                    finished_at = ? WHERE id = ?
                """,
                code,
                summary,
                Timestamp.from(now),
                attemptId
        );
        finishJob(jobId, LlmAnalysisJobStatus.FAILED, code, summary, now);
    }

    private void finishJob(
            UUID jobId,
            LlmAnalysisJobStatus status,
            String code,
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
                code,
                summary,
                Timestamp.from(now),
                jobId
        );
    }

    private String violationsJson(List<LlmValidationViolation> violations) {
        try {
            return objectMapper.writeValueAsString(violations);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "LLM validation violations cannot be encoded",
                    exception
            );
        }
    }

    private LlmAnalysisJob requireJob(UUID id) {
        return jobStore.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "LLM analysis job does not exist: " + id
        ));
    }
}
