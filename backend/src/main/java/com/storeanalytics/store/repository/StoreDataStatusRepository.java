package com.storeanalytics.store.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StoreDataStatusRepository {

    private static final String STATUS_QUERY = """
            WITH target_store AS (
                SELECT id, connection_id, timezone
                FROM stores
                WHERE id = :storeId
            ),
            sales_sync AS (
                SELECT run.period_end, run.finished_at
                FROM sync_runs run
                JOIN target_store store ON run.store_id = store.id
                    OR (run.store_id IS NULL AND store.connection_id = run.connection_id)
                WHERE run.sync_scope = 'SALES'
                  AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                  AND run.period_end IS NOT NULL
                ORDER BY run.period_end DESC, run.finished_at DESC
                LIMIT 1
            ),
            return_sync AS (
                SELECT run.period_end, run.finished_at
                FROM sync_runs run
                JOIN target_store store ON run.store_id = store.id
                    OR (run.store_id IS NULL AND store.connection_id = run.connection_id)
                WHERE run.sync_scope = 'RETURNS'
                  AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                  AND run.period_end IS NOT NULL
                ORDER BY run.period_end DESC, run.finished_at DESC
                LIMIT 1
            ),
            order_sync AS (
                SELECT run.period_end, run.finished_at
                FROM sync_runs run
                JOIN target_store store ON run.store_id = store.id
                    OR (run.store_id IS NULL AND store.connection_id = run.connection_id)
                WHERE run.sync_scope = 'ORDERS'
                  AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                  AND run.period_end IS NOT NULL
                ORDER BY run.period_end DESC, run.finished_at DESC
                LIMIT 1
            ),
            active_sync AS (
                SELECT activity.*
                FROM (
                    SELECT
                        job.id,
                        'JOB' AS activity_type,
                        job.status,
                        job.phase,
                        job.started_at,
                        job.next_attempt_at,
                        COALESCE(job.started_at, job.created_at) AS activity_order_at
                    FROM sync_jobs job
                    JOIN target_store store ON store.connection_id = job.connection_id
                    WHERE job.status IN ('PENDING', 'RUNNING', 'WAITING_RETRY')

                    UNION ALL

                    SELECT
                        run.id,
                        'DIRECT_RUN' AS activity_type,
                        run.status,
                        run.sync_scope AS phase,
                        run.started_at,
                        NULL AS next_attempt_at,
                        run.started_at AS activity_order_at
                    FROM sync_runs run
                    JOIN target_store store ON run.store_id = store.id
                        OR (run.store_id IS NULL AND store.connection_id = run.connection_id)
                    WHERE run.status = 'RUNNING'
                      AND run.sync_job_id IS NULL
                      AND run.sync_scope IN ('STORES', 'EMPLOYEES', 'SALES', 'RETURNS', 'ORDERS')
                ) activity
                ORDER BY activity.activity_order_at DESC
                LIMIT 1
            ),
            latest_terminal AS (
                SELECT activity.status, activity.finished_at
                FROM (
                    SELECT job.status, job.finished_at
                    FROM sync_jobs job
                    JOIN target_store store ON store.connection_id = job.connection_id
                    WHERE job.status IN ('SUCCESS', 'FAILED', 'CANCELLED')

                    UNION ALL

                    SELECT run.status, run.finished_at
                    FROM sync_runs run
                    JOIN target_store store ON run.store_id = store.id
                        OR (run.store_id IS NULL AND store.connection_id = run.connection_id)
                    WHERE run.sync_job_id IS NULL
                      AND run.sync_scope IN ('STORES', 'EMPLOYEES', 'SALES', 'RETURNS', 'ORDERS')
                      AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED')
                ) activity
                ORDER BY activity.finished_at DESC
                LIMIT 1
            ),
            last_failure AS (
                SELECT failure.error_summary, failure.failed_at
                FROM (
                    SELECT job.error_summary, job.finished_at AS failed_at
                    FROM sync_jobs job
                    JOIN target_store store ON store.connection_id = job.connection_id
                    WHERE job.status = 'FAILED'

                    UNION ALL

                    SELECT run.error_summary, run.finished_at AS failed_at
                    FROM sync_runs run
                    JOIN target_store store ON run.store_id = store.id
                        OR (run.store_id IS NULL AND store.connection_id = run.connection_id)
                    WHERE run.status = 'FAILED'
                      AND run.sync_scope IN ('STORES', 'EMPLOYEES', 'SALES', 'RETURNS', 'ORDERS')
                ) failure
                ORDER BY failure.failed_at DESC
                LIMIT 1
            )
            SELECT
                store.id AS store_id,
                store.timezone,
                sales.period_end AS sales_through_exclusive,
                sales.finished_at AS sales_completed_at,
                returns.period_end AS returns_through_exclusive,
                returns.finished_at AS returns_completed_at,
                orders.period_end AS orders_through_exclusive,
                orders.finished_at AS orders_completed_at,
                active.id AS active_sync_id,
                active.activity_type AS active_sync_type,
                active.status AS active_sync_status,
                active.phase AS active_sync_phase,
                active.started_at AS active_sync_started_at,
                active.next_attempt_at AS active_sync_next_attempt_at,
                terminal.status AS latest_terminal_status,
                terminal.finished_at AS latest_terminal_at,
                failure.error_summary AS last_error,
                failure.failed_at AS last_error_at,
                (
                    SELECT COUNT(*)
                    FROM data_quality_issues issue
                    WHERE issue.store_id = store.id
                      AND issue.status = 'OPEN'
                ) AS open_quality_issue_count
            FROM target_store store
            LEFT JOIN sales_sync sales ON true
            LEFT JOIN return_sync returns ON true
            LEFT JOIN order_sync orders ON true
            LEFT JOIN active_sync active ON true
            LEFT JOIN latest_terminal terminal ON true
            LEFT JOIN last_failure failure ON true
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StoreDataStatusRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<StoreDataStatusSnapshot> findByStoreId(UUID storeId) {
        return jdbcTemplate.query(
                STATUS_QUERY,
                Map.of("storeId", storeId),
                resultSet -> resultSet.next()
                        ? Optional.of(map(resultSet))
                        : Optional.empty()
        );
    }

    private StoreDataStatusSnapshot map(ResultSet resultSet) throws SQLException {
        return new StoreDataStatusSnapshot(
                resultSet.getObject("store_id", UUID.class),
                resultSet.getString("timezone"),
                instant(resultSet, "sales_through_exclusive"),
                instant(resultSet, "sales_completed_at"),
                instant(resultSet, "returns_through_exclusive"),
                instant(resultSet, "returns_completed_at"),
                instant(resultSet, "orders_through_exclusive"),
                instant(resultSet, "orders_completed_at"),
                resultSet.getObject("active_sync_id", UUID.class),
                resultSet.getString("active_sync_type"),
                resultSet.getString("active_sync_status"),
                resultSet.getString("active_sync_phase"),
                instant(resultSet, "active_sync_started_at"),
                instant(resultSet, "active_sync_next_attempt_at"),
                resultSet.getString("latest_terminal_status"),
                instant(resultSet, "latest_terminal_at"),
                resultSet.getString("last_error"),
                instant(resultSet, "last_error_at"),
                resultSet.getLong("open_quality_issue_count")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
