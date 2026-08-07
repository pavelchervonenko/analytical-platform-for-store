package com.storeanalytics.interpretation.operations;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.config.InterpretationFeatureProperties;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LlmOperationsQuery {

    private final JdbcTemplate jdbcTemplate;
    private final InterpretationFeatureProperties features;
    private final YandexLlmProperties yandex;
    private final Clock clock;

    public LlmOperationsQuery(
            JdbcTemplate jdbcTemplate,
            InterpretationFeatureProperties features,
            YandexLlmProperties yandex,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.features = features;
        this.yandex = yandex;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LlmOperationsView get(int incidentLimit) {
        if (incidentLimit < 1 || incidentLimit > 100) {
            throw new IllegalArgumentException("incidentLimit must be between 1 and 100");
        }
        Instant now = clock.instant();
        Instant since = now.minusSeconds(30L * 24 * 60 * 60);
        LlmOperationsSummaryView summary = jdbcTemplate.queryForObject(
                """
                SELECT
                    count(*) FILTER (WHERE job.status = 'PENDING') AS pending,
                    count(*) FILTER (WHERE job.status = 'WAITING_RETRY') AS waiting_retry,
                    count(*) FILTER (WHERE job.status = 'RUNNING') AS running,
                    count(*) FILTER (
                        WHERE job.status = 'RUNNING' AND job.lease_until <= ?
                    ) AS overdue_running,
                    count(*) FILTER (WHERE job.status = 'FAILED') AS failed,
                    count(*) FILTER (
                        WHERE job.status = 'VALIDATION_FAILED'
                    ) AS validation_failed,
                    count(*) FILTER (
                        WHERE job.status = 'SUCCESS' AND job.finished_at >= ?
                    ) AS succeeded_30d,
                    min(job.next_attempt_at) FILTER (
                        WHERE job.status IN ('PENDING', 'WAITING_RETRY')
                          AND job.next_attempt_at <= ?
                    ) AS oldest_ready_at,
                    (SELECT count(*) FROM llm_analysis_attempts attempt
                     WHERE attempt.started_at >= ?) AS provider_calls_30d,
                    (SELECT COALESCE(sum(attempt.input_tokens), 0)
                     FROM llm_analysis_attempts attempt
                     WHERE attempt.started_at >= ?) AS input_tokens_30d,
                    (SELECT COALESCE(sum(attempt.output_tokens), 0)
                     FROM llm_analysis_attempts attempt
                     WHERE attempt.started_at >= ?) AS output_tokens_30d,
                    (SELECT COALESCE(sum(attempt.cost_amount), 0)
                     FROM llm_analysis_attempts attempt
                     WHERE attempt.started_at >= ?) AS known_cost_30d,
                    (SELECT CASE WHEN count(DISTINCT attempt.cost_currency) = 1
                                 THEN max(attempt.cost_currency) ELSE NULL END
                     FROM llm_analysis_attempts attempt
                     WHERE attempt.started_at >= ?
                       AND attempt.cost_amount IS NOT NULL) AS cost_currency
                FROM llm_analysis_jobs job
                """,
                this::mapSummary,
                Timestamp.from(now), Timestamp.from(since), Timestamp.from(now),
                Timestamp.from(since), Timestamp.from(since), Timestamp.from(since),
                Timestamp.from(since), Timestamp.from(since)
        );
        List<LlmJobIncidentView> incidents = jdbcTemplate.query(
                """
                SELECT job.id, job.snapshot_id, snapshot.store_id, store.name AS store_name,
                       snapshot.period_start, snapshot.period_end,
                       snapshot.revision AS snapshot_revision,
                       job.generation_revision, job.trigger_type, job.status, job.phase,
                       job.attempt_count, job.transport_retry_count,
                       job.validation_retry_count, job.next_attempt_at, job.deadline_at,
                       job.cancel_requested, job.terminal_reason_code, job.error_summary,
                       latest_attempt.status AS last_attempt_status,
                       latest_attempt.http_status AS last_http_status,
                       job.updated_at
                FROM llm_analysis_jobs job
                JOIN analytics_snapshots snapshot ON snapshot.id = job.snapshot_id
                JOIN stores store ON store.id = snapshot.store_id
                LEFT JOIN LATERAL (
                    SELECT attempt.status, attempt.http_status
                    FROM llm_analysis_attempts attempt
                    WHERE attempt.job_id = job.id
                    ORDER BY attempt.attempt_number DESC
                    LIMIT 1
                ) latest_attempt ON true
                WHERE job.status IN (
                    'PENDING', 'RUNNING', 'WAITING_RETRY',
                    'SUCCESS', 'FAILED', 'VALIDATION_FAILED'
                )
                ORDER BY CASE job.status
                    WHEN 'RUNNING' THEN 0
                    WHEN 'WAITING_RETRY' THEN 1
                    WHEN 'PENDING' THEN 2
                    WHEN 'VALIDATION_FAILED' THEN 3
                    WHEN 'FAILED' THEN 3
                    ELSE 4
                END, job.updated_at DESC, job.id
                LIMIT ?
                """,
                this::mapIncident,
                incidentLimit
        );
        return new LlmOperationsView(
                now,
                new LlmOperationsConfigurationView(
                        features.snapshotEnabled(),
                        features.generationEnabled(),
                        features.publicationEnabled(),
                        yandex.isReadyForGeneration(),
                        modelName(yandex.getModelUri())
                ),
                summary,
                incidents
        );
    }

    private LlmOperationsSummaryView mapSummary(ResultSet resultSet, int rowNumber)
            throws SQLException {
        long overdue = resultSet.getLong("overdue_running");
        long failed = resultSet.getLong("failed");
        long invalid = resultSet.getLong("validation_failed");
        long waiting = resultSet.getLong("waiting_retry");
        String attention = overdue > 0 || failed > 0 || invalid > 0
                ? "CRITICAL" : waiting > 0 ? "WARNING" : "NORMAL";
        return new LlmOperationsSummaryView(
                attention,
                resultSet.getLong("pending"),
                waiting,
                resultSet.getLong("running"),
                overdue,
                failed,
                invalid,
                resultSet.getLong("succeeded_30d"),
                resultSet.getLong("provider_calls_30d"),
                resultSet.getLong("input_tokens_30d"),
                resultSet.getLong("output_tokens_30d"),
                resultSet.getBigDecimal("known_cost_30d"),
                resultSet.getString("cost_currency"),
                instant(resultSet, "oldest_ready_at")
        );
    }

    private LlmJobIncidentView mapIncident(ResultSet resultSet, int rowNumber)
            throws SQLException {
        int httpStatus = resultSet.getInt("last_http_status");
        return new LlmJobIncidentView(
                resultSet.getObject("id", java.util.UUID.class),
                resultSet.getObject("snapshot_id", java.util.UUID.class),
                resultSet.getObject("store_id", java.util.UUID.class),
                resultSet.getString("store_name"),
                resultSet.getObject("period_start", java.time.LocalDate.class),
                resultSet.getObject("period_end", java.time.LocalDate.class),
                resultSet.getInt("snapshot_revision"),
                resultSet.getInt("generation_revision"),
                resultSet.getString("trigger_type"),
                resultSet.getString("status"),
                resultSet.getString("phase"),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("transport_retry_count"),
                resultSet.getInt("validation_retry_count"),
                instant(resultSet, "next_attempt_at"),
                instant(resultSet, "deadline_at"),
                resultSet.getBoolean("cancel_requested"),
                resultSet.getString("terminal_reason_code"),
                resultSet.getString("error_summary"),
                resultSet.getString("last_attempt_status"),
                resultSet.wasNull() ? null : httpStatus,
                instant(resultSet, "updated_at")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static String modelName(String modelUri) {
        if (modelUri == null || modelUri.isBlank()) {
            return null;
        }
        String[] segments = modelUri.split("/");
        return segments.length < 2 ? "configured" : segments[segments.length - 1];
    }
}
