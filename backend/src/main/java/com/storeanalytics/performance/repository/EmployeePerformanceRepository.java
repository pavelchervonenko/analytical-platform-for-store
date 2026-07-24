package com.storeanalytics.performance.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeePerformanceRepository {

    private static final String EMPLOYEE_PERFORMANCE_QUERY = """
            WITH included_items AS (
                SELECT
                    document.employee_id,
                    CASE document.document_kind WHEN 'SALE' THEN 1 ELSE -1 END AS sign,
                    item.net_amount,
                    category.category_kind,
                    category.counts_as_additional_revenue
                FROM sales_documents document
                JOIN sales_document_items item ON item.sales_document_id = document.id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE document.store_id = :storeId
                  AND document.business_date BETWEEN :periodStart AND :periodEnd
                  AND NOT document.is_deleted
                  AND NOT item.is_deleted
                  AND category.code <> 'EXCLUDE'
                  AND document.employee_id IS NOT NULL
            ),
            employee_facts AS (
                SELECT
                    employee_id,
                    COALESCE(SUM(sign * net_amount), 0) AS net_revenue,
                    COALESCE(SUM(sign * net_amount) FILTER (
                        WHERE category_kind = 'ACCESSORY'
                    ), 0) AS accessory_revenue,
                    COALESCE(SUM(sign * net_amount) FILTER (
                        WHERE category_kind IN ('SERVICE', 'WARRANTY', 'PROTECTION')
                    ), 0) AS service_revenue,
                    COALESCE(SUM(sign * net_amount) FILTER (
                        WHERE counts_as_additional_revenue
                    ), 0) AS additional_revenue
                FROM included_items
                GROUP BY employee_id
            ),
            shift_facts AS (
                SELECT
                    employee_id,
                    COUNT(*) AS shift_count,
                    COALESCE(SUM(worked_hours), 0) AS worked_hours
                FROM employee_work_shifts
                WHERE store_id = :storeId
                  AND work_date BETWEEN :periodStart AND :periodEnd
                  AND is_active
                GROUP BY employee_id
            )
            SELECT
                employee.id AS employee_id,
                employee.full_name AS display_name,
                employee.is_active AS employee_active,
                assignment.is_active AS assignment_active,
                assignment.participates_in_ranking,
                COALESCE(facts.net_revenue, 0) AS net_revenue,
                COALESCE(facts.accessory_revenue, 0) AS accessory_revenue,
                COALESCE(facts.service_revenue, 0) AS service_revenue,
                COALESCE(facts.additional_revenue, 0) AS additional_revenue,
                COALESCE(shifts.shift_count, 0) AS shift_count,
                COALESCE(shifts.worked_hours, 0) AS worked_hours
            FROM employee_store_assignments assignment
            JOIN employees employee ON employee.id = assignment.employee_id
            LEFT JOIN employee_facts facts ON facts.employee_id = employee.id
            LEFT JOIN shift_facts shifts ON shifts.employee_id = employee.id
            WHERE assignment.store_id = :storeId
            ORDER BY employee.full_name, employee.id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EmployeePerformanceRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EmployeePerformanceAggregate> aggregate(
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
                EMPLOYEE_PERFORMANCE_QUERY,
                parameters,
                (resultSet, rowNumber) -> new EmployeePerformanceAggregate(
                        resultSet.getObject("employee_id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getBoolean("employee_active"),
                        resultSet.getBoolean("assignment_active"),
                        resultSet.getBoolean("participates_in_ranking"),
                        resultSet.getBigDecimal("net_revenue"),
                        resultSet.getBigDecimal("accessory_revenue"),
                        resultSet.getBigDecimal("service_revenue"),
                        resultSet.getBigDecimal("additional_revenue"),
                        resultSet.getLong("shift_count"),
                        resultSet.getBigDecimal("worked_hours")
                )
        );
    }
}
