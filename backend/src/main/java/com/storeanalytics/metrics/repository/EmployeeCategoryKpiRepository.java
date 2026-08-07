package com.storeanalytics.metrics.repository;

import com.storeanalytics.product.model.AnalyticsCategoryKind;
import com.storeanalytics.product.model.DeviceFamily;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeCategoryKpiRepository {

    private static final String EMPLOYEE_CATEGORY_QUERY = """
            WITH included_items AS (
                SELECT
                    document.employee_id,
                    item.analytics_category_id,
                    CASE document.document_kind WHEN 'SALE' THEN 1 ELSE -1 END AS sign,
                    item.quantity,
                    item.net_amount,
                    item.cost_amount,
                    item.cost_quality
                FROM sales_documents document
                JOIN sales_document_items item ON item.sales_document_id = document.id
                JOIN analytics_categories category
                  ON category.id = item.analytics_category_id
                WHERE document.store_id = :storeId
                  AND document.business_date BETWEEN :periodStart AND :periodEnd
                  AND NOT document.is_deleted
                  AND NOT item.is_deleted
                  AND category.code <> 'EXCLUDE'
            ),
            category_facts AS (
                SELECT
                    employee_id,
                    analytics_category_id,
                    COALESCE(SUM(sign * net_amount), 0) AS net_revenue,
                    COALESCE(SUM(sign * quantity), 0) AS net_quantity,
                    COALESCE(SUM(sign * COALESCE(cost_amount, 0)), 0) AS cost_amount,
                    COUNT(*) AS included_item_count,
                    COUNT(*) FILTER (
                        WHERE cost_amount IS NULL
                    ) AS missing_cost_item_count,
                    COUNT(*) FILTER (
                        WHERE cost_quality = 'ZERO_UNEXPECTED'
                    ) AS unexpected_zero_cost_item_count
                FROM included_items
                GROUP BY employee_id, analytics_category_id
            ),
            employee_ids AS (
                SELECT employee_id, false AS unassigned
                FROM employee_store_assignments
                WHERE store_id = :storeId

                UNION

                SELECT employee_id, false AS unassigned
                FROM category_facts
                WHERE employee_id IS NOT NULL

                UNION ALL

                SELECT NULL::uuid AS employee_id, true AS unassigned
                WHERE EXISTS (
                    SELECT 1
                    FROM category_facts
                    WHERE employee_id IS NULL
                )
            ),
            employee_context AS (
                SELECT
                    identity.employee_id,
                    employee.full_name AS display_name,
                    COALESCE(employee.is_active, false) AS employee_active,
                    assignment.employee_id IS NOT NULL AS assigned_to_store,
                    COALESCE(assignment.is_active, false) AS assignment_active,
                    COALESCE(assignment.participates_in_ranking, false)
                        AS participates_in_ranking,
                    identity.unassigned
                FROM employee_ids identity
                LEFT JOIN employees employee ON employee.id = identity.employee_id
                LEFT JOIN employee_store_assignments assignment
                  ON assignment.employee_id = identity.employee_id
                 AND assignment.store_id = :storeId
            )
            SELECT
                context.employee_id,
                context.display_name,
                context.employee_active,
                context.assigned_to_store,
                context.assignment_active,
                context.participates_in_ranking,
                context.unassigned,
                category.code AS category_code,
                category.name AS category_name,
                category.category_kind,
                category.device_family,
                category.is_active AS category_active,
                category.counts_as_phone,
                category.counts_as_device,
                category.counts_as_additional_revenue,
                COALESCE(facts.net_revenue, 0) AS net_revenue,
                COALESCE(facts.net_quantity, 0) AS net_quantity,
                COALESCE(facts.cost_amount, 0) AS cost_amount,
                COALESCE(facts.included_item_count, 0) AS included_item_count,
                COALESCE(facts.missing_cost_item_count, 0) AS missing_cost_item_count,
                COALESCE(
                    facts.unexpected_zero_cost_item_count,
                    0
                ) AS unexpected_zero_cost_item_count
            FROM employee_context context
            CROSS JOIN analytics_categories category
            LEFT JOIN category_facts facts
              ON facts.employee_id IS NOT DISTINCT FROM context.employee_id
             AND facts.analytics_category_id = category.id
            WHERE category.code <> 'EXCLUDE'
            ORDER BY
                context.unassigned,
                context.display_name,
                context.employee_id,
                category.category_kind,
                category.name,
                category.code
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EmployeeCategoryKpiRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EmployeeCategoryKpiAggregate> aggregate(
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
                EMPLOYEE_CATEGORY_QUERY,
                parameters,
                (resultSet, rowNumber) -> new EmployeeCategoryKpiAggregate(
                        resultSet.getObject("employee_id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getBoolean("employee_active"),
                        resultSet.getBoolean("assigned_to_store"),
                        resultSet.getBoolean("assignment_active"),
                        resultSet.getBoolean("participates_in_ranking"),
                        resultSet.getBoolean("unassigned"),
                        resultSet.getString("category_code"),
                        resultSet.getString("category_name"),
                        AnalyticsCategoryKind.valueOf(resultSet.getString("category_kind")),
                        DeviceFamily.valueOf(resultSet.getString("device_family")),
                        resultSet.getBoolean("category_active"),
                        resultSet.getBoolean("counts_as_phone"),
                        resultSet.getBoolean("counts_as_device"),
                        resultSet.getBoolean("counts_as_additional_revenue"),
                        resultSet.getBigDecimal("net_revenue"),
                        resultSet.getBigDecimal("net_quantity"),
                        resultSet.getBigDecimal("cost_amount"),
                        resultSet.getLong("included_item_count"),
                        resultSet.getLong("missing_cost_item_count"),
                        resultSet.getLong("unexpected_zero_cost_item_count")
                )
        );
    }
}
