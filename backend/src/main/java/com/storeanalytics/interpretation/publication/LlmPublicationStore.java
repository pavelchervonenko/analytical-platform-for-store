package com.storeanalytics.interpretation.publication;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;
import static com.storeanalytics.common.validation.ModelValidation.requireText;

import com.storeanalytics.interpretation.generation.LlmAnalysisJob;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisJobStore;
import com.storeanalytics.interpretation.generation.LlmAnalysisPhase;
import com.storeanalytics.interpretation.generation.LlmAnalysisTriggerType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class LlmPublicationStore {

    static final String NOTIFICATION_POLICY_VERSION = "weekly-notification-v1";
    private static final Duration NOTIFICATION_TTL = Duration.ofHours(24);

    private final JdbcTemplate jdbcTemplate;
    private final LlmAnalysisJobStore jobStore;
    private final ObjectMapper objectMapper;

    public LlmPublicationStore(
            JdbcTemplate jdbcTemplate,
            LlmAnalysisJobStore jobStore,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jobStore = jobStore;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LlmPublicationResult publish(
            UUID jobId,
            UUID attemptId,
            String owner,
            WeeklyPublicationMaterial material,
            Instant now
    ) {
        Instant timestamp = requireNonNull(now, "now");
        PublicationSource source = requireSource(jobId, attemptId, owner, timestamp);
        lockStore(source.storeId());
        PreviousInterpretation previous = findPrevious(source).orElse(null);
        int revision = previous == null ? 1 : previous.revision() + 1;
        UUID interpretationId = UUID.randomUUID();
        WeeklyPublicationMaterial content = requireNonNull(material, "material");
        insertInterpretation(
                interpretationId, source, previous, revision, content, timestamp
        );
        UUID eventId = insertNotificationEvent(
                interpretationId, source, revision, timestamp
        );
        jdbcTemplate.update(
                """
                UPDATE llm_analysis_jobs
                SET status = 'SUCCESS', next_attempt_at = ?,
                    lease_owner = NULL, lease_until = NULL,
                    terminal_reason_code = NULL, error_summary = NULL,
                    finished_at = ?, version = version + 1
                WHERE id = ?
                """,
                Timestamp.from(timestamp),
                Timestamp.from(timestamp),
                source.jobId()
        );
        return new LlmPublicationResult(
                requireJob(source.jobId()),
                interpretationId,
                eventId,
                revision
        );
    }

    private PublicationSource requireSource(
            UUID jobId,
            UUID attemptId,
            String owner,
            Instant now
    ) {
        List<PublicationSource> sources = jdbcTemplate.query(
                """
                SELECT job.id AS job_id, job.status, job.phase, job.trigger_type,
                       job.lease_owner, job.cancel_requested, job.deadline_at,
                       snapshot.id AS snapshot_id, snapshot.store_id,
                       snapshot.period_start, snapshot.period_end,
                       attempt.id AS attempt_id
                FROM llm_analysis_jobs job
                JOIN analytics_snapshots snapshot ON snapshot.id = job.snapshot_id
                JOIN llm_analysis_attempts attempt ON attempt.job_id = job.id
                WHERE job.id = ? AND attempt.id = ? AND attempt.status = 'SUCCEEDED'
                FOR UPDATE OF job, snapshot, attempt
                """,
                (resultSet, rowNumber) -> new PublicationSource(
                        resultSet.getObject("job_id", UUID.class),
                        LlmAnalysisJobStatus.valueOf(resultSet.getString("status")),
                        LlmAnalysisPhase.valueOf(resultSet.getString("phase")),
                        LlmAnalysisTriggerType.valueOf(
                                resultSet.getString("trigger_type")
                        ),
                        resultSet.getString("lease_owner"),
                        resultSet.getBoolean("cancel_requested"),
                        resultSet.getTimestamp("deadline_at").toInstant(),
                        resultSet.getObject("snapshot_id", UUID.class),
                        resultSet.getObject("store_id", UUID.class),
                        resultSet.getObject("period_start", LocalDate.class),
                        resultSet.getObject("period_end", LocalDate.class),
                        resultSet.getObject("attempt_id", UUID.class)
                ),
                requireNonNull(jobId, "jobId"),
                requireNonNull(attemptId, "attemptId")
        );
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "Publication source does not exist or was not validated"
            );
        }
        PublicationSource source = sources.getFirst();
        require(source.status() == LlmAnalysisJobStatus.RUNNING,
                "publication job must be RUNNING");
        require(source.phase() == LlmAnalysisPhase.PUBLISH,
                "publication job must be in PUBLISH phase");
        require(requireText(owner, "owner").equals(source.leaseOwner()),
                "publication lease is owned elsewhere");
        require(!source.cancelRequested(), "publication cancellation was requested");
        require(source.deadlineAt().isAfter(now),
                "publication deadline has passed");
        return source;
    }

    private void lockStore(UUID storeId) {
        List<UUID> ids = jdbcTemplate.query(
                "SELECT id FROM stores WHERE id = ? FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                storeId
        );
        require(!ids.isEmpty(), "publication store does not exist");
    }

    private java.util.Optional<PreviousInterpretation> findPrevious(
            PublicationSource source
    ) {
        List<PreviousInterpretation> values = jdbcTemplate.query(
                """
                SELECT id, revision
                FROM llm_interpretations
                WHERE store_id = ? AND interpretation_type = 'WEEKLY'
                  AND period_start = ? AND period_end = ?
                ORDER BY revision DESC
                LIMIT 1
                """,
                (resultSet, rowNumber) -> new PreviousInterpretation(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("revision")
                ),
                source.storeId(),
                source.periodStart(),
                source.periodEnd()
        );
        return values.isEmpty()
                ? java.util.Optional.empty()
                : java.util.Optional.of(values.getFirst());
    }

    private void insertInterpretation(
            UUID interpretationId,
            PublicationSource source,
            PreviousInterpretation previous,
            int revision,
            WeeklyPublicationMaterial material,
            Instant now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO llm_interpretations (
                    id, store_id, snapshot_id, analysis_job_id,
                    successful_attempt_id, interpretation_type,
                    period_start, period_end, revision,
                    supersedes_interpretation_id, publication_reason_code,
                    content_payload, content_hash, validated_at, published_at
                ) VALUES (
                    ?, ?, ?, ?, ?, 'WEEKLY', ?, ?, ?, ?, ?,
                    CAST(? AS jsonb), ?, ?, ?
                )
                """,
                interpretationId,
                source.storeId(),
                source.snapshotId(),
                source.jobId(),
                source.attemptId(),
                source.periodStart(),
                source.periodEnd(),
                revision,
                previous == null ? null : previous.id(),
                source.triggerType().name(),
                material.canonicalContent(),
                material.contentHash(),
                Timestamp.from(material.validatedAt()),
                Timestamp.from(now)
        );
    }

    private UUID insertNotificationEvent(
            UUID interpretationId,
            PublicationSource source,
            int revision,
            Instant now
    ) {
        UUID eventId = UUID.randomUUID();
        String eventType = revision == 1
                ? "WEEKLY_REPORT_READY" : "WEEKLY_REPORT_REVISED";
        NotificationEventPayload payload = new NotificationEventPayload(
                1,
                interpretationId,
                source.snapshotId(),
                revision,
                source.periodStart(),
                source.periodEnd()
        );
        String payloadJson = json(payload);
        jdbcTemplate.update(
                """
                INSERT INTO notification_events (
                    id, store_id, event_type, audience, interpretation_id,
                    snapshot_id, deduplication_key, notification_policy_version,
                    priority, event_payload, payload_hash, not_before, expires_at
                ) VALUES (
                    ?, ?, ?, 'MANAGER', ?, ?, ?, ?, 'NORMAL',
                    CAST(? AS jsonb), ?, ?, ?
                )
                """,
                eventId,
                source.storeId(),
                eventType,
                interpretationId,
                source.snapshotId(),
                "weekly-report:" + interpretationId + ":" + NOTIFICATION_POLICY_VERSION,
                NOTIFICATION_POLICY_VERSION,
                payloadJson,
                sha256(payloadJson),
                Timestamp.from(now),
                Timestamp.from(now.plus(NOTIFICATION_TTL))
        );
        return eventId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                    "Weekly notification event cannot be encoded",
                    exception
            );
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            ));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private LlmAnalysisJob requireJob(UUID id) {
        return jobStore.findById(id).orElseThrow(() -> new IllegalArgumentException(
                "LLM analysis job does not exist: " + id
        ));
    }

    private record PublicationSource(
            UUID jobId,
            LlmAnalysisJobStatus status,
            LlmAnalysisPhase phase,
            LlmAnalysisTriggerType triggerType,
            String leaseOwner,
            boolean cancelRequested,
            Instant deadlineAt,
            UUID snapshotId,
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd,
            UUID attemptId
    ) {
    }

    private record PreviousInterpretation(UUID id, int revision) {
    }

    private record NotificationEventPayload(
            int schemaVersion,
            UUID interpretationId,
            UUID snapshotId,
            int interpretationRevision,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
    }
}
