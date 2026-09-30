package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads all relevant sync activity, not just the one shown in the store status widget. */
@Repository
class SellerWeeklySourceStabilityRepository {

    private static final String QUERY = """
            WITH target_store AS (
                SELECT id, connection_id FROM stores WHERE id = :storeId
            ), active AS (
                SELECT 1 FROM sync_jobs job JOIN target_store store
                    ON store.connection_id = job.connection_id
                WHERE job.status IN ('PENDING', 'RUNNING', 'WAITING_RETRY')
                  AND (job.phase IN ('STORES', 'EMPLOYEES')
                       OR (job.phase IN ('SALES', 'RETURNS', 'ORDERS')
                           AND job.period_start < :periodEnd AND job.period_end > :periodStart))
                UNION ALL
                SELECT 1 FROM sync_runs run JOIN target_store store
                    ON run.store_id = store.id
                    OR (run.store_id IS NULL AND run.connection_id = store.connection_id)
                WHERE run.sync_job_id IS NULL AND run.status IN ('PENDING', 'RUNNING')
                  AND run.sync_scope IN ('STORES', 'EMPLOYEES', 'PRODUCTS',
                                         'SALES', 'RETURNS', 'ORDERS', 'FULL', 'PERIOD')
                  AND (run.sync_scope IN ('STORES', 'EMPLOYEES', 'PRODUCTS')
                       OR run.period_start IS NULL OR run.period_end IS NULL
                       OR (run.period_start < :periodEnd AND run.period_end > :periodStart))
            ), failed AS (
                SELECT 1 FROM sync_jobs job JOIN target_store store
                    ON store.connection_id = job.connection_id
                WHERE job.status IN ('FAILED', 'CANCELLED')
                  AND (job.phase IN ('STORES', 'EMPLOYEES')
                       OR (job.phase IN ('SALES', 'RETURNS', 'ORDERS')
                           AND job.period_start < :periodEnd AND job.period_end > :periodStart))
                  AND NOT EXISTS (
                      SELECT 1 FROM sync_jobs later
                      WHERE later.connection_id = job.connection_id AND later.status = 'SUCCESS'
                        AND later.finished_at > job.finished_at
                      HAVING count(*) > 0 AND CASE
                          WHEN job.phase IN ('STORES', 'EMPLOYEES') THEN true
                          ELSE tstzrange(greatest(job.period_start, :periodStart),
                                         least(job.period_end, :periodEnd), '[)')
                              <@ range_agg(tstzrange(later.period_start, later.period_end, '[)'))
                      END
                  )
                UNION ALL
                SELECT 1 FROM sync_runs run JOIN target_store store
                    ON run.store_id = store.id
                    OR (run.store_id IS NULL AND run.connection_id = store.connection_id)
                WHERE run.status IN ('FAILED', 'PARTIAL_SUCCESS', 'CANCELLED')
                  AND run.sync_scope IN ('STORES', 'EMPLOYEES', 'PRODUCTS',
                                         'SALES', 'RETURNS', 'ORDERS', 'FULL', 'PERIOD')
                  AND (run.sync_scope IN ('STORES', 'EMPLOYEES', 'PRODUCTS')
                       OR run.period_start IS NULL OR run.period_end IS NULL
                       OR (run.period_start < :periodEnd AND run.period_end > :periodStart))
                  AND NOT EXISTS (
                      SELECT 1 FROM sync_runs later
                      WHERE later.status = 'SUCCESS' AND later.finished_at > run.finished_at
                        AND later.sync_scope = run.sync_scope
                        AND (later.store_id = store.id
                             OR (later.store_id IS NULL
                                 AND later.connection_id = store.connection_id))
                      HAVING count(*) > 0 AND CASE
                          WHEN run.sync_scope IN ('STORES', 'EMPLOYEES', 'PRODUCTS') THEN true
                          ELSE tstzrange(
                              CASE WHEN run.period_start IS NULL OR run.period_end IS NULL
                                   THEN :periodStart ELSE greatest(run.period_start, :periodStart) END,
                              CASE WHEN run.period_start IS NULL OR run.period_end IS NULL
                                   THEN :periodEnd ELSE least(run.period_end, :periodEnd) END, '[)')
                              <@ range_agg(tstzrange(later.period_start, later.period_end, '[)'))
                                  FILTER (WHERE later.period_start IS NOT NULL
                                          AND later.period_end IS NOT NULL
                                          AND later.period_end > later.period_start)
                      END
                  )
            )
            SELECT EXISTS (SELECT 1 FROM active) AS active,
                   EXISTS (SELECT 1 FROM failed) AS failed
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SellerWeeklySourceStabilityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SellerWeeklySourceStability read(UUID storeId, Instant periodStart, Instant periodEnd) {
        Map<String, Object> params = Map.of(
                "storeId", requireNonNull(storeId, "storeId"),
                "periodStart", Timestamp.from(requireNonNull(periodStart, "periodStart")),
                "periodEnd", Timestamp.from(requireNonNull(periodEnd, "periodEnd")));
        return jdbc.queryForObject(QUERY, params, (row, index) -> {
            if (row.getBoolean("active")) {
                return SellerWeeklySourceStability.IN_PROGRESS;
            }
            return row.getBoolean("failed")
                    ? SellerWeeklySourceStability.NEEDS_RECONCILIATION
                    : SellerWeeklySourceStability.STABLE;
        });
    }
}
