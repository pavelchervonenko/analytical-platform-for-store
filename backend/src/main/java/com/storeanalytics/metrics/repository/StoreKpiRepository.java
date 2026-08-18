package com.storeanalytics.metrics.repository;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StoreKpiRepository {

    private static final String STORE_KPI_QUERY = """
            WITH included_items AS (
                SELECT
                    CASE sd.document_kind WHEN 'SALE' THEN 1 ELSE -1 END AS sign,
                    sdi.quantity,
                    sdi.net_amount,
                    sdi.cost_amount,
                    sdi.cost_quality,
                    ac.code AS category_code
                FROM sales_documents sd
                JOIN sales_document_items sdi ON sdi.sales_document_id = sd.id
                JOIN analytics_categories ac ON ac.id = sdi.analytics_category_id
                WHERE sd.store_id = :storeId
                  AND sd.business_date BETWEEN :periodStart AND :periodEnd
                  AND NOT sd.is_deleted
                  AND NOT sdi.is_deleted
                  AND ac.code <> 'EXCLUDE'
            )
            SELECT
                COALESCE(SUM(sign * net_amount), 0) AS net_revenue,
                COALESCE(SUM(sign * quantity), 0) AS net_quantity,
                COALESCE(SUM(sign * COALESCE(cost_amount, 0)), 0) AS cost_amount,
                COUNT(*) AS included_item_count,
                COUNT(*) FILTER (WHERE category_code = 'UNMAPPED') AS unmapped_item_count,
                COUNT(*) FILTER (WHERE cost_amount IS NULL) AS missing_cost_item_count,
                COUNT(*) FILTER (
                    WHERE cost_quality = 'ZERO_UNEXPECTED'
                ) AS unexpected_zero_cost_item_count,
                (
                    SELECT COUNT(*)
                    FROM data_quality_issues dqi
                    WHERE dqi.store_id = :storeId
                      AND dqi.status = 'OPEN'
                      AND dqi.issue_code NOT IN (
                          'ZERO_UNEXPECTED_COST',
                          'RETURN_ZERO_UNEXPECTED_COST'
                      )
                ) AS store_open_quality_issue_count
            FROM included_items
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StoreKpiRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StoreKpiAggregate aggregate(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        Map<String, Object> parameters = Map.of(
                "storeId", storeId,
                "periodStart", periodStart,
                "periodEnd", periodEnd
        );
        return jdbcTemplate.queryForObject(
                STORE_KPI_QUERY,
                parameters,
                (resultSet, rowNumber) -> new StoreKpiAggregate(
                        resultSet.getBigDecimal("net_revenue"),
                        resultSet.getBigDecimal("net_quantity"),
                        resultSet.getBigDecimal("cost_amount"),
                        resultSet.getLong("included_item_count"),
                        resultSet.getLong("unmapped_item_count"),
                        resultSet.getLong("missing_cost_item_count"),
                        resultSet.getLong("unexpected_zero_cost_item_count"),
                        resultSet.getLong("store_open_quality_issue_count")
                )
        );
    }
}
