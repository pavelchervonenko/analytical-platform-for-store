package com.storeanalytics.metrics.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AverageKpiRepository {

    private static final String AVERAGE_KPI_QUERY = """
            WITH periods(period_code, period_start, period_end) AS (
                VALUES
                    ('CURRENT', CAST(:currentStart AS date), CAST(:currentEnd AS date)),
                    ('PREVIOUS', CAST(:previousStart AS date), CAST(:previousEnd AS date))
            ),
            period_documents AS (
                SELECT
                    period.period_code,
                    document.id,
                    document.document_kind
                FROM periods period
                JOIN sales_documents document
                  ON document.store_id = :storeId
                 AND document.business_date BETWEEN period.period_start AND period.period_end
                 AND NOT document.is_deleted
            ),
            receipt_totals AS (
                SELECT
                    period_code,
                    COUNT(*) FILTER (WHERE document_kind = 'SALE') AS receipt_count
                FROM period_documents
                GROUP BY period_code
            ),
            included_items AS (
                SELECT
                    document.period_code,
                    item.analytics_category_id,
                    category.counts_as_phone,
                    category.counts_as_additional_revenue,
                    CASE document.document_kind WHEN 'SALE' THEN 1 ELSE -1 END AS sign,
                    item.quantity,
                    item.net_amount
                FROM period_documents document
                JOIN sales_document_items item ON item.sales_document_id = document.id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE NOT item.is_deleted
                  AND category.code <> 'EXCLUDE'
            ),
            item_totals AS (
                SELECT
                    period_code,
                    COALESCE(SUM(sign * net_amount), 0) AS net_revenue,
                    COALESCE(
                        SUM(sign * net_amount) FILTER (
                            WHERE counts_as_additional_revenue
                        ),
                        0
                    ) AS additional_revenue,
                    COALESCE(
                        SUM(sign * quantity) FILTER (WHERE counts_as_phone),
                        0
                    ) AS phone_quantity
                FROM included_items
                GROUP BY period_code
            ),
            category_facts AS (
                SELECT
                    period_code,
                    analytics_category_id,
                    COALESCE(SUM(sign * net_amount), 0) AS net_revenue,
                    COALESCE(SUM(sign * quantity), 0) AS net_quantity
                FROM included_items
                GROUP BY period_code, analytics_category_id
            ),
            period_totals AS (
                SELECT
                    period.period_code,
                    COALESCE(items.net_revenue, 0) AS net_revenue,
                    COALESCE(receipts.receipt_count, 0) AS receipt_count,
                    COALESCE(items.additional_revenue, 0) AS additional_revenue,
                    COALESCE(items.phone_quantity, 0) AS phone_quantity
                FROM periods period
                LEFT JOIN item_totals items USING (period_code)
                LEFT JOIN receipt_totals receipts USING (period_code)
            ),
            store_summary AS (
                SELECT
                    MAX(net_revenue) FILTER (
                        WHERE period_code = 'CURRENT'
                    ) AS current_net_revenue,
                    MAX(receipt_count) FILTER (
                        WHERE period_code = 'CURRENT'
                    ) AS current_receipt_count,
                    MAX(additional_revenue) FILTER (
                        WHERE period_code = 'CURRENT'
                    ) AS current_additional_revenue,
                    MAX(phone_quantity) FILTER (
                        WHERE period_code = 'CURRENT'
                    ) AS current_phone_quantity,
                    MAX(net_revenue) FILTER (
                        WHERE period_code = 'PREVIOUS'
                    ) AS previous_net_revenue,
                    MAX(receipt_count) FILTER (
                        WHERE period_code = 'PREVIOUS'
                    ) AS previous_receipt_count,
                    MAX(additional_revenue) FILTER (
                        WHERE period_code = 'PREVIOUS'
                    ) AS previous_additional_revenue,
                    MAX(phone_quantity) FILTER (
                        WHERE period_code = 'PREVIOUS'
                    ) AS previous_phone_quantity
                FROM period_totals
            )
            SELECT
                category.code AS category_code,
                category.name AS category_name,
                category.is_active AS category_active,
                COALESCE(current_facts.net_revenue, 0) AS current_category_revenue,
                COALESCE(current_facts.net_quantity, 0) AS current_category_quantity,
                COALESCE(previous_facts.net_revenue, 0) AS previous_category_revenue,
                COALESCE(previous_facts.net_quantity, 0) AS previous_category_quantity,
                summary.current_net_revenue,
                summary.current_receipt_count,
                summary.current_additional_revenue,
                summary.current_phone_quantity,
                summary.previous_net_revenue,
                summary.previous_receipt_count,
                summary.previous_additional_revenue,
                summary.previous_phone_quantity
            FROM analytics_categories category
            LEFT JOIN category_facts current_facts
              ON current_facts.analytics_category_id = category.id
             AND current_facts.period_code = 'CURRENT'
            LEFT JOIN category_facts previous_facts
              ON previous_facts.analytics_category_id = category.id
             AND previous_facts.period_code = 'PREVIOUS'
            CROSS JOIN store_summary summary
            WHERE category.code <> 'EXCLUDE'
            ORDER BY category.category_kind, category.name, category.code
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AverageKpiRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AverageKpiAggregate> aggregate(
            UUID storeId,
            LocalDate currentStart,
            LocalDate currentEnd,
            LocalDate previousStart,
            LocalDate previousEnd
    ) {
        Map<String, Object> parameters = Map.of(
                "storeId", storeId,
                "currentStart", currentStart,
                "currentEnd", currentEnd,
                "previousStart", previousStart,
                "previousEnd", previousEnd
        );
        return jdbcTemplate.query(
                AVERAGE_KPI_QUERY,
                parameters,
                (resultSet, rowNumber) -> new AverageKpiAggregate(
                        resultSet.getString("category_code"),
                        resultSet.getString("category_name"),
                        resultSet.getBoolean("category_active"),
                        resultSet.getBigDecimal("current_category_revenue"),
                        resultSet.getBigDecimal("current_category_quantity"),
                        resultSet.getBigDecimal("previous_category_revenue"),
                        resultSet.getBigDecimal("previous_category_quantity"),
                        resultSet.getBigDecimal("current_net_revenue"),
                        resultSet.getLong("current_receipt_count"),
                        resultSet.getBigDecimal("current_additional_revenue"),
                        resultSet.getBigDecimal("current_phone_quantity"),
                        resultSet.getBigDecimal("previous_net_revenue"),
                        resultSet.getLong("previous_receipt_count"),
                        resultSet.getBigDecimal("previous_additional_revenue"),
                        resultSet.getBigDecimal("previous_phone_quantity")
                )
        );
    }
}
