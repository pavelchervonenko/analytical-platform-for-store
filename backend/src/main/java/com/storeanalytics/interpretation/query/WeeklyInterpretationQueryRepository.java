package com.storeanalytics.interpretation.query;

import com.storeanalytics.interpretation.contract.LlmCanonicalJsonCodec;
import com.storeanalytics.interpretation.contract.WeeklyInterpretationInput.QualityStatus;
import com.storeanalytics.interpretation.generation.LlmAnalysisTriggerType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WeeklyInterpretationQueryRepository {

    private static final String SUMMARY_COLUMNS = """
            interpretation.id, interpretation.store_id,
            interpretation.snapshot_id, interpretation.period_start,
            interpretation.period_end, snapshot.timezone,
            snapshot.revision AS snapshot_revision,
            interpretation.revision AS interpretation_revision,
            interpretation.supersedes_interpretation_id,
            interpretation.publication_reason_code,
            interpretation.content_hash, job.content_schema_version, snapshot.quality_status,
            (SELECT count(*) FROM analytics_snapshot_employees employee
             WHERE employee.snapshot_id = interpretation.snapshot_id)
                AS employee_count,
            interpretation.validated_at, interpretation.published_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final LlmCanonicalJsonCodec jsonCodec;

    public WeeklyInterpretationQueryRepository(
            JdbcTemplate jdbcTemplate,
            LlmCanonicalJsonCodec jsonCodec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonCodec = jsonCodec;
    }

    public Optional<WeeklyInterpretationDetailView> findLatest(UUID storeId) {
        return detail("""
                SELECT %s,
                       true AS current_revision,
                       interpretation.content_payload::text AS content_payload
                FROM llm_interpretations interpretation
                JOIN analytics_snapshots snapshot
                  ON snapshot.id = interpretation.snapshot_id
                JOIN llm_analysis_jobs job
                  ON job.id = interpretation.analysis_job_id
                WHERE interpretation.store_id = ?
                  AND interpretation.interpretation_type = 'WEEKLY'
                ORDER BY interpretation.period_end DESC,
                         interpretation.period_start DESC,
                         interpretation.revision DESC
                LIMIT 1
                """.formatted(SUMMARY_COLUMNS), storeId);
    }

    public Optional<WeeklyInterpretationDetailView> findLatestForPeriod(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        return detail("""
                SELECT %s,
                       true AS current_revision,
                       interpretation.content_payload::text AS content_payload
                FROM llm_interpretations interpretation
                JOIN analytics_snapshots snapshot
                  ON snapshot.id = interpretation.snapshot_id
                JOIN llm_analysis_jobs job
                  ON job.id = interpretation.analysis_job_id
                WHERE interpretation.store_id = ?
                  AND interpretation.interpretation_type = 'WEEKLY'
                  AND interpretation.period_start = ?
                  AND interpretation.period_end = ?
                ORDER BY interpretation.revision DESC
                LIMIT 1
                """.formatted(SUMMARY_COLUMNS), storeId, periodStart, periodEnd);
    }

    public Optional<WeeklyInterpretationDetailView> findById(
            UUID storeId,
            UUID interpretationId
    ) {
        return detail("""
                SELECT %s,
                       NOT EXISTS (
                           SELECT 1
                           FROM llm_interpretations newer
                           WHERE newer.store_id = interpretation.store_id
                             AND newer.interpretation_type =
                                 interpretation.interpretation_type
                             AND newer.period_start = interpretation.period_start
                             AND newer.period_end = interpretation.period_end
                             AND newer.revision > interpretation.revision
                       ) AS current_revision,
                       interpretation.content_payload::text AS content_payload
                FROM llm_interpretations interpretation
                JOIN analytics_snapshots snapshot
                  ON snapshot.id = interpretation.snapshot_id
                JOIN llm_analysis_jobs job
                  ON job.id = interpretation.analysis_job_id
                WHERE interpretation.store_id = ?
                  AND interpretation.id = ?
                  AND interpretation.interpretation_type = 'WEEKLY'
                """.formatted(SUMMARY_COLUMNS), storeId, interpretationId);
    }

    public List<WeeklyInterpretationSummaryView> listCurrent(
            UUID storeId,
            LocalDate periodStartFrom,
            LocalDate periodEndTo,
            int limit,
            long offset
    ) {
        return jdbcTemplate.query("""
                WITH ranked AS (
                    SELECT %s,
                           row_number() OVER (
                               PARTITION BY interpretation.store_id,
                                            interpretation.interpretation_type,
                                            interpretation.period_start,
                                            interpretation.period_end
                               ORDER BY interpretation.revision DESC
                           ) AS revision_rank
                    FROM llm_interpretations interpretation
                    JOIN analytics_snapshots snapshot
                      ON snapshot.id = interpretation.snapshot_id
                    JOIN llm_analysis_jobs job
                      ON job.id = interpretation.analysis_job_id
                    WHERE interpretation.store_id = ?
                      AND interpretation.interpretation_type = 'WEEKLY'
                      AND interpretation.period_start >= ?
                      AND interpretation.period_end <= ?
                )
                SELECT *, true AS current_revision
                FROM ranked
                WHERE revision_rank = 1
                ORDER BY period_end DESC, period_start DESC, id
                LIMIT ? OFFSET ?
                """.formatted(SUMMARY_COLUMNS),
                this::mapSummary,
                storeId,
                periodStartFrom,
                periodEndTo,
                limit,
                offset
        );
    }

    public long countCurrent(
            UUID storeId,
            LocalDate periodStartFrom,
            LocalDate periodEndTo
    ) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM (
                    SELECT interpretation.period_start, interpretation.period_end
                    FROM llm_interpretations interpretation
                    WHERE interpretation.store_id = ?
                      AND interpretation.interpretation_type = 'WEEKLY'
                      AND interpretation.period_start >= ?
                      AND interpretation.period_end <= ?
                    GROUP BY interpretation.period_start, interpretation.period_end
                ) periods
                """,
                Long.class,
                storeId,
                periodStartFrom,
                periodEndTo
        );
        return count == null ? 0 : count;
    }

    private Optional<WeeklyInterpretationDetailView> detail(
            String sql,
            Object... arguments
    ) {
        List<DetailRow> rows = jdbcTemplate.query(
                sql,
                this::mapDetail,
                arguments
        );
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        DetailRow row = rows.getFirst();
        return Optional.of(new WeeklyInterpretationDetailView(
                row.summary(),
                jsonCodec.decodeVerified(row.contentPayload(), row.contentHash()),
                employees(row.summary().snapshotId())
        ));
    }

    private List<WeeklyInterpretationEmployeeView> employees(UUID snapshotId) {
        return jdbcTemplate.query("""
                SELECT employee_ref, employee_id, display_name_snapshot
                FROM analytics_snapshot_employees
                WHERE snapshot_id = ?
                ORDER BY employee_ref
                """,
                (resultSet, rowNumber) -> new WeeklyInterpretationEmployeeView(
                        resultSet.getString("employee_ref"),
                        resultSet.getObject("employee_id", UUID.class),
                        resultSet.getString("display_name_snapshot")
                ),
                snapshotId
        );
    }

    private DetailRow mapDetail(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new DetailRow(
                mapSummary(resultSet, rowNumber),
                resultSet.getString("content_payload"),
                resultSet.getString("content_hash")
        );
    }

    private WeeklyInterpretationSummaryView mapSummary(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new WeeklyInterpretationSummaryView(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("store_id", UUID.class),
                resultSet.getObject("snapshot_id", UUID.class),
                resultSet.getObject("period_start", LocalDate.class),
                resultSet.getObject("period_end", LocalDate.class),
                resultSet.getString("timezone"),
                resultSet.getInt("snapshot_revision"),
                resultSet.getInt("interpretation_revision"),
                resultSet.getBoolean("current_revision"),
                resultSet.getObject("supersedes_interpretation_id", UUID.class),
                LlmAnalysisTriggerType.valueOf(
                        resultSet.getString("publication_reason_code")
                ),
                resultSet.getString("content_hash"),
                resultSet.getInt("content_schema_version"),
                QualityStatus.valueOf(resultSet.getString("quality_status")),
                resultSet.getInt("employee_count"),
                resultSet.getTimestamp("validated_at").toInstant(),
                resultSet.getTimestamp("published_at").toInstant()
        );
    }

    private record DetailRow(
            WeeklyInterpretationSummaryView summary,
            String contentPayload,
            String contentHash
    ) {
    }
}
