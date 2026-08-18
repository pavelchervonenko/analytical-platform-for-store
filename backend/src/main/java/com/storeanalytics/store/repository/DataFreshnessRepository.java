package com.storeanalytics.store.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DataFreshnessRepository {

    private static final String QUERY = """
            WITH store_freshness AS (
                SELECT
                    store.id,
                    MAX(run.period_end) FILTER (
                        WHERE run.sync_scope = 'SALES'
                          AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                    ) AS sales_through,
                    MAX(run.period_end) FILTER (
                        WHERE run.sync_scope = 'RETURNS'
                          AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                    ) AS returns_through,
                    MAX(run.period_end) FILTER (
                        WHERE run.sync_scope = 'ORDERS'
                          AND run.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                    ) AS orders_through
                FROM stores store
                LEFT JOIN sync_runs run
                  ON run.store_id = store.id
                  OR (run.store_id IS NULL
                      AND run.connection_id = store.connection_id)
                WHERE store.is_active
                GROUP BY store.id
            )
            SELECT
                MIN(sales_through) AS oldest_sales_through,
                MIN(returns_through) AS oldest_returns_through,
                MIN(orders_through) AS oldest_orders_through,
                COUNT(*) FILTER (
                    WHERE sales_through IS NULL
                ) AS stores_without_sales,
                COUNT(*) FILTER (
                    WHERE returns_through IS NULL
                ) AS stores_without_returns,
                COUNT(*) FILTER (
                    WHERE orders_through IS NULL
                ) AS stores_without_orders
            FROM store_freshness
            """;

    private final JdbcTemplate jdbcTemplate;

    public DataFreshnessRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DataFreshnessSnapshot load() {
        return jdbcTemplate.queryForObject(QUERY, this::map);
    }

    private DataFreshnessSnapshot map(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new DataFreshnessSnapshot(
                instant(resultSet, "oldest_sales_through"),
                instant(resultSet, "oldest_returns_through"),
                instant(resultSet, "oldest_orders_through"),
                resultSet.getLong("stores_without_sales"),
                resultSet.getLong("stores_without_returns"),
                resultSet.getLong("stores_without_orders")
        );
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
