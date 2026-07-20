package com.storeanalytics.metrics.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeKpiRepository {

    private static final String EMPLOYEE_KPI_QUERY = """
            WITH included_items AS (
                SELECT
                    sd.employee_id,
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
            ),
            employee_facts AS (
                SELECT
                    employee_id,
                    COALESCE(SUM(sign * net_amount), 0) AS net_revenue,
                    COALESCE(SUM(sign * quantity), 0) AS net_quantity,
                    COALESCE(SUM(sign * COALESCE(cost_amount, 0)), 0) AS cost_amount,
                    COUNT(*) AS included_item_count,
                    COUNT(*) FILTER (
                        WHERE category_code = 'UNMAPPED'
                    ) AS unmapped_item_count,
                    COUNT(*) FILTER (
                        WHERE cost_amount IS NULL
                    ) AS missing_cost_item_count,
                    COUNT(*) FILTER (
                        WHERE cost_quality = 'ZERO_UNEXPECTED'
                    ) AS unexpected_zero_cost_item_count
                FROM included_items
                GROUP BY employee_id
            ),
            employee_ids AS (
                SELECT employee_id
                FROM employee_store_assignments
                WHERE store_id = :storeId
                UNION
                SELECT employee_id
                FROM employee_facts
                WHERE employee_id IS NOT NULL
            ),
            result_rows AS (
                SELECT
                    ids.employee_id,
                    e.full_name AS display_name,
                    e.is_active AS employee_active,
                    esa.employee_id IS NOT NULL AS assigned_to_store,
                    COALESCE(esa.is_active, false) AS assignment_active,
                    COALESCE(esa.participates_in_ranking, false) AS participates_in_ranking,
                    false AS unassigned,
                    COALESCE(ef.net_revenue, 0) AS net_revenue,
                    COALESCE(ef.net_quantity, 0) AS net_quantity,
                    COALESCE(ef.cost_amount, 0) AS cost_amount,
                    COALESCE(ef.included_item_count, 0) AS included_item_count,
                    COALESCE(ef.unmapped_item_count, 0) AS unmapped_item_count,
                    COALESCE(ef.missing_cost_item_count, 0) AS missing_cost_item_count,
                    COALESCE(
                        ef.unexpected_zero_cost_item_count, 0
                    ) AS unexpected_zero_cost_item_count
                FROM employee_ids ids
                JOIN employees e ON e.id = ids.employee_id
                LEFT JOIN employee_store_assignments esa
                  ON esa.employee_id = ids.employee_id
                 AND esa.store_id = :storeId
                LEFT JOIN employee_facts ef ON ef.employee_id = ids.employee_id

                UNION ALL

                SELECT
                    NULL AS employee_id,
                    NULL AS display_name,
                    false AS employee_active,
                    false AS assigned_to_store,
                    false AS assignment_active,
                    false AS participates_in_ranking,
                    true AS unassigned,
                    ef.net_revenue,
                    ef.net_quantity,
                    ef.cost_amount,
                    ef.included_item_count,
                    ef.unmapped_item_count,
                    ef.missing_cost_item_count,
                    ef.unexpected_zero_cost_item_count
                FROM employee_facts ef
                WHERE ef.employee_id IS NULL
                  AND ef.included_item_count > 0
            )
            SELECT *
            FROM result_rows
            ORDER BY unassigned, display_name, employee_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EmployeeKpiRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EmployeeKpiAggregate> aggregate(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        Map<String, Object> parameters = Map.of(
                "storeId", storeId,
                "periodStart", periodStart,
                "periodEnd", periodEnd
        );
        return jdbcTemplate.query(
                EMPLOYEE_KPI_QUERY,
                parameters,
                (resultSet, rowNumber) -> new EmployeeKpiAggregate(
                        resultSet.getObject("employee_id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getBoolean("employee_active"),
                        resultSet.getBoolean("assigned_to_store"),
                        resultSet.getBoolean("assignment_active"),
                        resultSet.getBoolean("participates_in_ranking"),
                        resultSet.getBoolean("unassigned"),
                        resultSet.getBigDecimal("net_revenue"),
                        resultSet.getBigDecimal("net_quantity"),
                        resultSet.getBigDecimal("cost_amount"),
                        resultSet.getLong("included_item_count"),
                        resultSet.getLong("unmapped_item_count"),
                        resultSet.getLong("missing_cost_item_count"),
                        resultSet.getLong("unexpected_zero_cost_item_count")
                )
        );
    }
}
