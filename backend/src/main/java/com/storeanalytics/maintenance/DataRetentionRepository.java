package com.storeanalytics.maintenance;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataRetentionRepository {

    private static final String RAW_CANDIDATES = """
            FROM raw_record_versions candidate
            WHERE (
                    candidate.normalization_status = 'NORMALIZED'
                    AND candidate.last_seen_at < :normalizedCutoff
                OR candidate.normalization_status IN ('FAILED', 'SKIPPED')
                    AND candidate.last_seen_at < :problemCutoff
            )
              AND EXISTS (
                  SELECT 1
                  FROM raw_record_versions newer
                  WHERE newer.connection_id IS NOT DISTINCT FROM candidate.connection_id
                    AND newer.store_id IS NOT DISTINCT FROM candidate.store_id
                    AND newer.source_system = candidate.source_system
                    AND newer.entity_type = candidate.entity_type
                    AND newer.external_id = candidate.external_id
                    AND (newer.last_seen_at, newer.id)
                        > (candidate.last_seen_at, candidate.id)
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM data_quality_issues issue
                  WHERE issue.status = 'OPEN'
                    AND issue.entity_type = candidate.entity_type
                    AND issue.entity_id = concat(
                        candidate.connection_id::text,
                        ':',
                        candidate.external_id
                    )
              )
            """;

    private static final String SYNC_RUN_CANDIDATES = """
            FROM sync_runs run
            WHERE (
                    run.status = 'SUCCESS'
                    AND run.finished_at < :successfulCutoff
                OR run.status IN ('PARTIAL_SUCCESS', 'FAILED', 'CANCELLED')
                    AND run.finished_at < :unsuccessfulCutoff
            )
              AND EXISTS (
                  SELECT 1
                  FROM sync_runs newer
                  WHERE newer.connection_id IS NOT DISTINCT FROM run.connection_id
                    AND newer.store_id IS NOT DISTINCT FROM run.store_id
                    AND newer.source_system = run.source_system
                    AND newer.sync_scope = run.sync_scope
                    AND newer.status IN (
                        'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED'
                    )
                    AND (newer.finished_at, newer.id) > (run.finished_at, run.id)
              )
              AND (
                  run.sync_scope NOT IN ('SALES', 'RETURNS')
                  OR run.status NOT IN ('SUCCESS', 'PARTIAL_SUCCESS')
                  OR EXISTS (
                      SELECT 1
                      FROM sync_runs fresher
                      WHERE fresher.connection_id IS NOT DISTINCT FROM run.connection_id
                        AND fresher.store_id IS NOT DISTINCT FROM run.store_id
                        AND fresher.source_system = run.source_system
                        AND fresher.sync_scope = run.sync_scope
                        AND fresher.status IN (
                            'SUCCESS', 'PARTIAL_SUCCESS'
                        )
                        AND fresher.period_end IS NOT NULL
                        AND (
                            run.period_end IS NULL
                            OR (fresher.period_end, fresher.finished_at, fresher.id)
                                > (run.period_end, run.finished_at, run.id)
                        )
                  )
              )
            """;

    private static final String SYNC_JOB_CANDIDATES = """
            FROM sync_jobs job
            WHERE (
                    job.status = 'SUCCESS'
                    AND job.finished_at < :successfulCutoff
                OR job.status IN ('FAILED', 'CANCELLED')
                    AND job.finished_at < :unsuccessfulCutoff
            )
              AND EXISTS (
                  SELECT 1
                  FROM sync_jobs newer
                  WHERE newer.connection_id = job.connection_id
                    AND newer.job_type = job.job_type
                    AND newer.status IN ('SUCCESS', 'FAILED', 'CANCELLED')
                    AND (newer.finished_at, newer.id) > (job.finished_at, job.id)
              )
            """;

    private static final String DAILY_ROLLUP_SQL = """
            WITH candidate_groups AS MATERIALIZED (
                SELECT
                    source.store_id,
                    source.product_id,
                    source.snapshot_date,
                    min(source.observed_at) AS first_observation
                FROM (
                    SELECT
                        history.store_id,
                        history.product_id,
                        (history.observed_at AT TIME ZONE :zone)::date AS snapshot_date,
                        history.observed_at
                    FROM store_product_inventory_history history
                    WHERE history.observed_at < :cutoff
                ) source
                GROUP BY
                    source.store_id,
                    source.product_id,
                    source.snapshot_date
                ORDER BY first_observation
                LIMIT :batchSize
            ),
            aggregated AS MATERIALIZED (
                SELECT
                    history.store_id,
                    history.product_id,
                    group_entry.snapshot_date,
                    (array_agg(
                        history.quantity
                        ORDER BY history.observed_at, history.id
                    ))[1] AS opening_quantity,
                    (array_agg(
                        history.quantity
                        ORDER BY history.observed_at DESC, history.id DESC
                    ))[1] AS closing_quantity,
                    min(history.quantity) AS minimum_quantity,
                    max(history.quantity) AS maximum_quantity,
                    (array_agg(
                        history.retail_price
                        ORDER BY history.observed_at DESC, history.id DESC
                    ))[1] AS closing_retail_price,
                    (array_agg(
                        history.cost_amount
                        ORDER BY history.observed_at DESC, history.id DESC
                    ))[1] AS closing_cost_amount,
                    bool_or(history.quantity <= 0) AS was_out_of_stock,
                    count(*) AS observation_count,
                    min(history.observed_at) AS first_observed_at,
                    max(history.observed_at) AS last_observed_at
                FROM store_product_inventory_history history
                JOIN candidate_groups group_entry
                  ON group_entry.store_id = history.store_id
                 AND group_entry.product_id = history.product_id
                 AND group_entry.snapshot_date =
                    (history.observed_at AT TIME ZONE :zone)::date
                WHERE history.observed_at < :cutoff
                GROUP BY
                    history.store_id,
                    history.product_id,
                    group_entry.snapshot_date
            ),
            upserted AS (
                INSERT INTO store_product_inventory_daily (
                    store_id,
                    product_id,
                    snapshot_date,
                    opening_quantity,
                    closing_quantity,
                    minimum_quantity,
                    maximum_quantity,
                    closing_retail_price,
                    closing_cost_amount,
                    was_out_of_stock,
                    observation_count,
                    first_observed_at,
                    last_observed_at
                )
                SELECT
                    store_id,
                    product_id,
                    snapshot_date,
                    opening_quantity,
                    closing_quantity,
                    minimum_quantity,
                    maximum_quantity,
                    closing_retail_price,
                    closing_cost_amount,
                    was_out_of_stock,
                    observation_count,
                    first_observed_at,
                    last_observed_at
                FROM aggregated
                ON CONFLICT (store_id, product_id, snapshot_date) DO UPDATE SET
                    opening_quantity = CASE
                        WHEN EXCLUDED.first_observed_at
                                < store_product_inventory_daily.first_observed_at
                            THEN EXCLUDED.opening_quantity
                        ELSE store_product_inventory_daily.opening_quantity
                    END,
                    closing_quantity = CASE
                        WHEN EXCLUDED.last_observed_at
                                > store_product_inventory_daily.last_observed_at
                            THEN EXCLUDED.closing_quantity
                        ELSE store_product_inventory_daily.closing_quantity
                    END,
                    closing_retail_price = CASE
                        WHEN EXCLUDED.last_observed_at
                                > store_product_inventory_daily.last_observed_at
                            THEN EXCLUDED.closing_retail_price
                        ELSE store_product_inventory_daily.closing_retail_price
                    END,
                    closing_cost_amount = CASE
                        WHEN EXCLUDED.last_observed_at
                                > store_product_inventory_daily.last_observed_at
                            THEN EXCLUDED.closing_cost_amount
                        ELSE store_product_inventory_daily.closing_cost_amount
                    END,
                    minimum_quantity = least(
                        store_product_inventory_daily.minimum_quantity,
                        EXCLUDED.minimum_quantity
                    ),
                    maximum_quantity = greatest(
                        store_product_inventory_daily.maximum_quantity,
                        EXCLUDED.maximum_quantity
                    ),
                    was_out_of_stock =
                        store_product_inventory_daily.was_out_of_stock
                        OR EXCLUDED.was_out_of_stock,
                    observation_count =
                        store_product_inventory_daily.observation_count
                        + EXCLUDED.observation_count,
                    first_observed_at = least(
                        store_product_inventory_daily.first_observed_at,
                        EXCLUDED.first_observed_at
                    ),
                    last_observed_at = greatest(
                        store_product_inventory_daily.last_observed_at,
                        EXCLUDED.last_observed_at
                    )
                RETURNING store_id, product_id, snapshot_date
            ),
            deleted AS (
                DELETE FROM store_product_inventory_history history
                USING upserted rollup
                WHERE history.store_id = rollup.store_id
                  AND history.product_id = rollup.product_id
                  AND (history.observed_at AT TIME ZONE :zone)::date =
                    rollup.snapshot_date
                  AND history.observed_at < :cutoff
                RETURNING history.id
            )
            SELECT
                (SELECT count(*) FROM upserted) AS rollups,
                (SELECT count(*) FROM deleted) AS deleted
            """;

    private static final String MONTHLY_ROLLUP_SQL = """
            WITH candidate_groups AS MATERIALIZED (
                SELECT
                    daily.store_id,
                    daily.product_id,
                    date_trunc('month', daily.snapshot_date)::date AS month_start,
                    min(daily.snapshot_date) AS first_date
                FROM store_product_inventory_daily daily
                WHERE daily.snapshot_date < :cutoff
                GROUP BY
                    daily.store_id,
                    daily.product_id,
                    date_trunc('month', daily.snapshot_date)::date
                ORDER BY first_date
                LIMIT :batchSize
            ),
            aggregated AS MATERIALIZED (
                SELECT
                    daily.store_id,
                    daily.product_id,
                    group_entry.month_start,
                    (array_agg(
                        daily.opening_quantity
                        ORDER BY daily.first_observed_at, daily.snapshot_date
                    ))[1] AS opening_quantity,
                    (array_agg(
                        daily.closing_quantity
                        ORDER BY daily.last_observed_at DESC, daily.snapshot_date DESC
                    ))[1] AS closing_quantity,
                    min(daily.minimum_quantity) AS minimum_quantity,
                    max(daily.maximum_quantity) AS maximum_quantity,
                    (array_agg(
                        daily.closing_retail_price
                        ORDER BY daily.last_observed_at DESC, daily.snapshot_date DESC
                    ))[1] AS closing_retail_price,
                    (array_agg(
                        daily.closing_cost_amount
                        ORDER BY daily.last_observed_at DESC, daily.snapshot_date DESC
                    ))[1] AS closing_cost_amount,
                    count(*) FILTER (WHERE daily.was_out_of_stock) AS days_out_of_stock,
                    count(*) AS observed_days,
                    sum(daily.observation_count) AS observation_count,
                    min(daily.first_observed_at) AS first_observed_at,
                    max(daily.last_observed_at) AS last_observed_at
                FROM store_product_inventory_daily daily
                JOIN candidate_groups group_entry
                  ON group_entry.store_id = daily.store_id
                 AND group_entry.product_id = daily.product_id
                 AND group_entry.month_start =
                    date_trunc('month', daily.snapshot_date)::date
                WHERE daily.snapshot_date < :cutoff
                GROUP BY
                    daily.store_id,
                    daily.product_id,
                    group_entry.month_start
            ),
            upserted AS (
                INSERT INTO store_product_inventory_monthly (
                    store_id,
                    product_id,
                    month_start,
                    opening_quantity,
                    closing_quantity,
                    minimum_quantity,
                    maximum_quantity,
                    closing_retail_price,
                    closing_cost_amount,
                    days_out_of_stock,
                    observed_days,
                    observation_count,
                    first_observed_at,
                    last_observed_at
                )
                SELECT
                    store_id,
                    product_id,
                    month_start,
                    opening_quantity,
                    closing_quantity,
                    minimum_quantity,
                    maximum_quantity,
                    closing_retail_price,
                    closing_cost_amount,
                    days_out_of_stock,
                    observed_days,
                    observation_count,
                    first_observed_at,
                    last_observed_at
                FROM aggregated
                ON CONFLICT (store_id, product_id, month_start) DO UPDATE SET
                    opening_quantity = CASE
                        WHEN EXCLUDED.first_observed_at
                                < store_product_inventory_monthly.first_observed_at
                            THEN EXCLUDED.opening_quantity
                        ELSE store_product_inventory_monthly.opening_quantity
                    END,
                    closing_quantity = CASE
                        WHEN EXCLUDED.last_observed_at
                                > store_product_inventory_monthly.last_observed_at
                            THEN EXCLUDED.closing_quantity
                        ELSE store_product_inventory_monthly.closing_quantity
                    END,
                    closing_retail_price = CASE
                        WHEN EXCLUDED.last_observed_at
                                > store_product_inventory_monthly.last_observed_at
                            THEN EXCLUDED.closing_retail_price
                        ELSE store_product_inventory_monthly.closing_retail_price
                    END,
                    closing_cost_amount = CASE
                        WHEN EXCLUDED.last_observed_at
                                > store_product_inventory_monthly.last_observed_at
                            THEN EXCLUDED.closing_cost_amount
                        ELSE store_product_inventory_monthly.closing_cost_amount
                    END,
                    minimum_quantity = least(
                        store_product_inventory_monthly.minimum_quantity,
                        EXCLUDED.minimum_quantity
                    ),
                    maximum_quantity = greatest(
                        store_product_inventory_monthly.maximum_quantity,
                        EXCLUDED.maximum_quantity
                    ),
                    days_out_of_stock =
                        store_product_inventory_monthly.days_out_of_stock
                        + EXCLUDED.days_out_of_stock,
                    observed_days =
                        store_product_inventory_monthly.observed_days
                        + EXCLUDED.observed_days,
                    observation_count =
                        store_product_inventory_monthly.observation_count
                        + EXCLUDED.observation_count,
                    first_observed_at = least(
                        store_product_inventory_monthly.first_observed_at,
                        EXCLUDED.first_observed_at
                    ),
                    last_observed_at = greatest(
                        store_product_inventory_monthly.last_observed_at,
                        EXCLUDED.last_observed_at
                    )
                RETURNING store_id, product_id, month_start
            ),
            deleted AS (
                DELETE FROM store_product_inventory_daily daily
                USING upserted rollup
                WHERE daily.store_id = rollup.store_id
                  AND daily.product_id = rollup.product_id
                  AND date_trunc('month', daily.snapshot_date)::date =
                    rollup.month_start
                  AND daily.snapshot_date < :cutoff
                RETURNING daily.store_id
            )
            SELECT
                (SELECT count(*) FROM upserted) AS rollups,
                (SELECT count(*) FROM deleted) AS deleted
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DataRetentionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean tryAcquireLock() {
        Boolean acquired = jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(1937006964, 20260724)",
                Map.of(),
                Boolean.class
        );
        return Boolean.TRUE.equals(acquired);
    }

    public long countLegacyRawVersions() {
        return count(
                "SELECT count(*) FROM raw_record_versions WHERE payload_policy_version = 0",
                Map.of()
        );
    }

    public long countRawVersions(Instant normalizedCutoff, Instant problemCutoff) {
        return count(
                "SELECT count(*) " + RAW_CANDIDATES,
                rawParameters(normalizedCutoff, problemCutoff)
        );
    }

    public long purgeRawVersions(
            Instant normalizedCutoff,
            Instant problemCutoff,
            int batchSize
    ) {
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT candidate.id
                """ + RAW_CANDIDATES + """
                    ORDER BY candidate.last_seen_at, candidate.id
                    LIMIT :batchSize
                    FOR UPDATE OF candidate SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM raw_record_versions target
                    USING candidates
                    WHERE target.id = candidates.id
                    RETURNING target.id
                )
                SELECT count(*) FROM deleted
                """;
        MapSqlParameterSource parameters = rawParameters(normalizedCutoff, problemCutoff)
                .addValue("batchSize", batchSize);
        return count(sql, parameters);
    }

    public long countSyncRuns(Instant successfulCutoff, Instant unsuccessfulCutoff) {
        return count(
                "SELECT count(*) " + SYNC_RUN_CANDIDATES,
                syncParameters(successfulCutoff, unsuccessfulCutoff)
        );
    }

    public long countSyncRunErrors(
            Instant successfulCutoff,
            Instant unsuccessfulCutoff
    ) {
        String sql = "SELECT count(*) FROM sync_run_errors error "
                + "JOIN (SELECT run.id "
                + SYNC_RUN_CANDIDATES
                + ") candidate ON candidate.id = error.sync_run_id";
        return count(sql, syncParameters(successfulCutoff, unsuccessfulCutoff));
    }

    public SyncRunPurgeResult purgeSyncRuns(
            Instant successfulCutoff,
            Instant unsuccessfulCutoff,
            int batchSize
    ) {
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT run.id
                """ + SYNC_RUN_CANDIDATES + """
                    ORDER BY run.finished_at, run.id
                    LIMIT :batchSize
                    FOR UPDATE OF run SKIP LOCKED
                ),
                error_count AS MATERIALIZED (
                    SELECT count(*) AS value
                    FROM sync_run_errors error
                    JOIN candidates ON candidates.id = error.sync_run_id
                ),
                deleted AS (
                    DELETE FROM sync_runs target
                    USING candidates
                    WHERE target.id = candidates.id
                    RETURNING target.id
                )
                SELECT
                    (SELECT count(*) FROM deleted) AS runs,
                    (SELECT value FROM error_count) AS errors
                """;
        MapSqlParameterSource parameters = syncParameters(
                successfulCutoff,
                unsuccessfulCutoff
        ).addValue("batchSize", batchSize);
        return jdbcTemplate.queryForObject(
                sql,
                parameters,
                (resultSet, rowNumber) -> new SyncRunPurgeResult(
                        resultSet.getLong("runs"),
                        resultSet.getLong("errors")
                )
        );
    }

    public long countSyncJobs(Instant successfulCutoff, Instant unsuccessfulCutoff) {
        return count(
                "SELECT count(*) " + SYNC_JOB_CANDIDATES,
                syncParameters(successfulCutoff, unsuccessfulCutoff)
        );
    }

    public long purgeSyncJobs(
            Instant successfulCutoff,
            Instant unsuccessfulCutoff,
            int batchSize
    ) {
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT job.id
                """ + SYNC_JOB_CANDIDATES + """
                    ORDER BY job.finished_at, job.id
                    LIMIT :batchSize
                    FOR UPDATE OF job SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM sync_jobs target
                    USING candidates
                    WHERE target.id = candidates.id
                    RETURNING target.id
                )
                SELECT count(*) FROM deleted
                """;
        MapSqlParameterSource parameters = syncParameters(
                successfulCutoff,
                unsuccessfulCutoff
        ).addValue("batchSize", batchSize);
        return count(sql, parameters);
    }

    public long countDetailedInventory(Instant cutoff) {
        return count(
                """
                SELECT count(*)
                FROM store_product_inventory_history
                WHERE observed_at < :cutoff
                """,
                Map.of("cutoff", Timestamp.from(cutoff))
        );
    }

    public RetentionBatchResult rollupDetailedInventory(
            Instant cutoff,
            ZoneId zone,
            int batchSize
    ) {
        return rollup(
                DAILY_ROLLUP_SQL,
                new MapSqlParameterSource()
                        .addValue("cutoff", Timestamp.from(cutoff))
                        .addValue("zone", zone.getId())
                        .addValue("batchSize", batchSize)
        );
    }

    public long countDailyInventory(LocalDate cutoff) {
        return count(
                """
                SELECT count(*)
                FROM store_product_inventory_daily
                WHERE snapshot_date < :cutoff
                """,
                Map.of("cutoff", Date.valueOf(cutoff))
        );
    }

    public RetentionBatchResult rollupDailyInventory(
            LocalDate cutoff,
            int batchSize
    ) {
        return rollup(
                MONTHLY_ROLLUP_SQL,
                new MapSqlParameterSource()
                        .addValue("cutoff", Date.valueOf(cutoff))
                        .addValue("batchSize", batchSize)
        );
    }

    public long countClosedQualityIssues(Instant cutoff) {
        return count(
                """
                SELECT count(*)
                FROM data_quality_issues
                WHERE status IN ('RESOLVED', 'IGNORED')
                  AND resolved_at < :cutoff
                """,
                Map.of("cutoff", Timestamp.from(cutoff))
        );
    }

    public long purgeClosedQualityIssues(Instant cutoff, int batchSize) {
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT id
                    FROM data_quality_issues
                    WHERE status IN ('RESOLVED', 'IGNORED')
                      AND resolved_at < :cutoff
                    ORDER BY resolved_at, id
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM data_quality_issues target
                    USING candidates
                    WHERE target.id = candidates.id
                    RETURNING target.id
                )
                SELECT count(*) FROM deleted
                """;
        return count(
                sql,
                new MapSqlParameterSource()
                        .addValue("cutoff", Timestamp.from(cutoff))
                        .addValue("batchSize", batchSize)
        );
    }

    public long countExpiredAuditEntries(Instant now) {
        return count(
                """
                SELECT count(*)
                FROM audit_log audit
                WHERE audit.retention_class <> 'FINANCIAL'
                  AND audit.retain_until <= :now
                  AND NOT EXISTS (
                      SELECT 1
                      FROM audit_retention_holds hold_entry
                      WHERE hold_entry.audit_log_id = audit.id
                        AND hold_entry.released_at IS NULL
                  )
                """,
                Map.of("now", Timestamp.from(now))
        );
    }

    public long purgeExpiredAuditEntries(Instant now, int batchSize) {
        jdbcTemplate.queryForObject(
                "SELECT set_config('app.audit_retention_cleanup', 'on', true)",
                Map.of(),
                String.class
        );
        String sql = """
                WITH candidates AS MATERIALIZED (
                    SELECT audit.id
                    FROM audit_log audit
                    WHERE audit.retention_class <> 'FINANCIAL'
                      AND audit.retain_until <= :now
                      AND NOT EXISTS (
                          SELECT 1
                          FROM audit_retention_holds hold_entry
                          WHERE hold_entry.audit_log_id = audit.id
                            AND hold_entry.released_at IS NULL
                      )
                    ORDER BY audit.retain_until, audit.id
                    LIMIT :batchSize
                    FOR UPDATE OF audit SKIP LOCKED
                ),
                deleted AS (
                    DELETE FROM audit_log target
                    USING candidates
                    WHERE target.id = candidates.id
                    RETURNING target.id
                )
                SELECT count(*) FROM deleted
                """;
        return count(
                sql,
                new MapSqlParameterSource()
                        .addValue("now", Timestamp.from(now))
                        .addValue("batchSize", batchSize)
        );
    }

    private RetentionBatchResult rollup(
            String sql,
            MapSqlParameterSource parameters
    ) {
        return jdbcTemplate.queryForObject(
                sql,
                parameters,
                (resultSet, rowNumber) -> new RetentionBatchResult(
                        resultSet.getLong("rollups"),
                        resultSet.getLong("deleted")
                )
        );
    }

    private long count(String sql, Map<String, ?> parameters) {
        Long value = jdbcTemplate.queryForObject(sql, parameters, Long.class);
        return value == null ? 0 : value;
    }

    private long count(String sql, MapSqlParameterSource parameters) {
        Long value = jdbcTemplate.queryForObject(sql, parameters, Long.class);
        return value == null ? 0 : value;
    }

    private MapSqlParameterSource rawParameters(
            Instant normalizedCutoff,
            Instant problemCutoff
    ) {
        return new MapSqlParameterSource()
                .addValue("normalizedCutoff", Timestamp.from(normalizedCutoff))
                .addValue("problemCutoff", Timestamp.from(problemCutoff));
    }

    private MapSqlParameterSource syncParameters(
            Instant successfulCutoff,
            Instant unsuccessfulCutoff
    ) {
        return new MapSqlParameterSource()
                .addValue("successfulCutoff", Timestamp.from(successfulCutoff))
                .addValue("unsuccessfulCutoff", Timestamp.from(unsuccessfulCutoff));
    }
}
