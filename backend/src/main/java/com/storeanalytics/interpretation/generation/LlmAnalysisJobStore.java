package com.storeanalytics.interpretation.generation;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LlmAnalysisJobStore {

    private static final String BY_ID_SQL =
            "SELECT * FROM llm_analysis_jobs WHERE id = ?";
    private static final String REQUEST_SQL = """
            SELECT * FROM llm_analysis_jobs
            WHERE snapshot_id = ? AND generation_revision = ?
            """;
    private static final String LOCK_SNAPSHOT_SQL = """
            SELECT quality_status FROM analytics_snapshots WHERE id = ? FOR UPDATE
            """;

    private final JdbcTemplate jdbcTemplate;

    public LlmAnalysisJobStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public LlmAnalysisEnqueueResult enqueue(
            LlmAnalysisJobRequest request,
            Instant now
    ) {
        LlmAnalysisJobRequest value = requireNonNull(request, "request");
        Instant timestamp = requireNonNull(now, "now");
        require(value.deadlineAt().isAfter(timestamp),
                "deadlineAt must be after enqueue time");
        String qualityStatus = lockSnapshot(value.snapshotId());
        require(!"BLOCKED".equals(qualityStatus),
                "BLOCKED snapshot must not be sent to LLM");

        Optional<LlmAnalysisJob> existing = findRequest(value);
        if (existing.isPresent()) {
            requireMatching(existing.get(), value);
            return new LlmAnalysisEnqueueResult(existing.get(), false);
        }

        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO llm_analysis_jobs (
                    id, snapshot_id, generation_revision, trigger_type, requested_by,
                    provider_code, requested_model, provider_config_version,
                    content_schema_version, prompt_version, analysis_policy_version,
                    budget_policy_version, generation_parameters, input_hash,
                    status, phase, max_transport_retries, max_validation_retries,
                    next_attempt_at, deadline_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?,
                    'PENDING', 'PREPARE', ?, ?, ?, ?, ?, ?
                )
                """,
                id,
                value.snapshotId(),
                value.generationRevision(),
                value.triggerType().name(),
                value.requestedBy(),
                value.providerCode(),
                value.requestedModel(),
                value.providerConfigVersion(),
                value.contentSchemaVersion(),
                value.promptVersion(),
                value.analysisPolicyVersion(),
                value.budgetPolicyVersion(),
                value.generationParameters(),
                value.inputHash(),
                value.maxTransportRetries(),
                value.maxValidationRetries(),
                Timestamp.from(timestamp),
                Timestamp.from(value.deadlineAt()),
                Timestamp.from(timestamp),
                Timestamp.from(timestamp)
        );
        return new LlmAnalysisEnqueueResult(requireJob(id), true);
    }

    @Transactional(readOnly = true)
    public Optional<LlmAnalysisJob> findById(UUID jobId) {
        return single(jdbcTemplate.query(
                BY_ID_SQL,
                this::mapRow,
                requireNonNull(jobId, "jobId")
        ));
    }

    private String lockSnapshot(UUID snapshotId) {
        List<String> values = jdbcTemplate.query(
                LOCK_SNAPSHOT_SQL,
                (resultSet, rowNumber) -> resultSet.getString("quality_status"),
                snapshotId
        );
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Snapshot does not exist: " + snapshotId);
        }
        return values.getFirst();
    }

    private Optional<LlmAnalysisJob> findRequest(LlmAnalysisJobRequest request) {
        return single(jdbcTemplate.query(
                REQUEST_SQL,
                this::mapRow,
                request.snapshotId(),
                request.generationRevision()
        ));
    }

    private void requireMatching(
            LlmAnalysisJob job,
            LlmAnalysisJobRequest request
    ) {
        boolean matches = job.triggerType() == request.triggerType()
                && Objects.equals(job.requestedBy(), request.requestedBy())
                && job.providerCode().equals(request.providerCode())
                && job.requestedModel().equals(request.requestedModel())
                && job.providerConfigVersion().equals(request.providerConfigVersion())
                && job.contentSchemaVersion() == request.contentSchemaVersion()
                && job.promptVersion().equals(request.promptVersion())
                && job.analysisPolicyVersion().equals(request.analysisPolicyVersion())
                && job.budgetPolicyVersion().equals(request.budgetPolicyVersion())
                && job.inputHash().equals(request.inputHash())
                && job.maxTransportRetries() == request.maxTransportRetries()
                && job.maxValidationRetries() == request.maxValidationRetries();
        if (!matches) {
            throw new LlmAnalysisJobConflictException(
                    "Existing LLM analysis job has different request metadata"
            );
        }
    }

    private LlmAnalysisJob requireJob(UUID id) {
        return findById(id).orElseThrow(() -> new IllegalStateException(
                "Created LLM analysis job cannot be read: " + id
        ));
    }

    private LlmAnalysisJob mapRow(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new LlmAnalysisJob(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("snapshot_id", UUID.class),
                resultSet.getInt("generation_revision"),
                LlmAnalysisTriggerType.valueOf(resultSet.getString("trigger_type")),
                resultSet.getObject("requested_by", UUID.class),
                resultSet.getString("provider_code"),
                resultSet.getString("requested_model"),
                resultSet.getString("provider_config_version"),
                resultSet.getInt("content_schema_version"),
                resultSet.getString("prompt_version"),
                resultSet.getString("analysis_policy_version"),
                resultSet.getString("budget_policy_version"),
                resultSet.getString("generation_parameters"),
                resultSet.getString("input_hash"),
                LlmAnalysisJobStatus.valueOf(resultSet.getString("status")),
                LlmAnalysisPhase.valueOf(resultSet.getString("phase")),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("transport_retry_count"),
                resultSet.getInt("validation_retry_count"),
                resultSet.getInt("max_transport_retries"),
                resultSet.getInt("max_validation_retries"),
                instant(resultSet, "next_attempt_at"),
                instant(resultSet, "deadline_at"),
                resultSet.getString("lease_owner"),
                nullableInstant(resultSet, "lease_until"),
                resultSet.getBoolean("cancel_requested"),
                resultSet.getString("terminal_reason_code"),
                resultSet.getString("error_summary"),
                nullableInstant(resultSet, "started_at"),
                nullableInstant(resultSet, "finished_at"),
                resultSet.getLong("version"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at")
        );
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
}
