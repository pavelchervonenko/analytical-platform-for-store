package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Proves continuous SUCCESS coverage rather than trusting the largest sync end timestamp. */
@Repository
class SellerWeeklySourceCoverageRepository {

    private static final String QUERY = """
            WITH target_store AS (
                SELECT id, connection_id FROM stores WHERE id = :storeId
            ), covered AS (
                SELECT run.sync_scope,
                       range_agg(tstzrange(run.period_start, run.period_end, '[)')) AS intervals
                FROM sync_runs run JOIN target_store store
                    ON run.store_id = store.id
                    OR (run.store_id IS NULL AND run.connection_id = store.connection_id)
                WHERE run.sync_scope IN ('SALES', 'RETURNS', 'ORDERS')
                  AND run.status = 'SUCCESS'
                  AND run.period_start IS NOT NULL AND run.period_end IS NOT NULL
                  AND run.period_end > run.period_start
                GROUP BY run.sync_scope
            )
            SELECT source.scope,
                   coalesce(tstzrange(:currentStart, :currentEnd, '[)')
                       <@ covered.intervals, false) AS current_complete,
                   coalesce(tstzrange(:previousStart, :previousEnd, '[)')
                       <@ covered.intervals, false) AS previous_complete
            FROM (VALUES ('SALES'), ('RETURNS'), ('ORDERS')) source(scope)
            LEFT JOIN covered ON covered.sync_scope = source.scope
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SellerWeeklySourceCoverageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    SellerWeeklySourceCoverage read(UUID storeId, PeriodContext period) {
        PeriodContext selected = requireNonNull(period, "period");
        ZoneId zone = ZoneId.of(selected.timezone());
        Map<String, Object> params = Map.of(
                "storeId", requireNonNull(storeId, "storeId"),
                "currentStart", Timestamp.from(selected.current().start().atStartOfDay(zone).toInstant()),
                "currentEnd", Timestamp.from(selected.current().end().plusDays(1)
                        .atStartOfDay(zone).toInstant()),
                "previousStart", Timestamp.from(selected.previous().start().atStartOfDay(zone).toInstant()),
                "previousEnd", Timestamp.from(selected.previous().end().plusDays(1)
                        .atStartOfDay(zone).toInstant()));
        Map<String, SellerWeeklySourceCoverage.Window> windows = new HashMap<>();
        jdbc.query(QUERY, params, (row) -> {
            windows.put(row.getString("scope"),
                    new SellerWeeklySourceCoverage.Window(
                            row.getBoolean("current_complete"), row.getBoolean("previous_complete")));
        });
        return new SellerWeeklySourceCoverage(
                windows.get("SALES"), windows.get("RETURNS"), windows.get("ORDERS"));
    }
}
