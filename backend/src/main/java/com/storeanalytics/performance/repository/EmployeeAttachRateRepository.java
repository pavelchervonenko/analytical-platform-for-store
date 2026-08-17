package com.storeanalytics.performance.repository;

import com.storeanalytics.product.model.AttachDenominatorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeAttachRateRepository {

    private static final String EMPLOYEE_ATTACH_RATE_QUERY = """
            WITH rating_employees AS (
                SELECT assignment.employee_id
                FROM employee_store_assignments assignment
                JOIN employees employee ON employee.id = assignment.employee_id
                WHERE assignment.store_id = :storeId
                  AND assignment.is_active
                  AND assignment.participates_in_ranking
                  AND employee.is_active
            ),
            period_facts AS (
                SELECT fact.*
                FROM attach_rate_item_facts_v3 fact
                WHERE fact.store_id = :storeId
                  AND fact.business_date BETWEEN :periodStart AND :periodEnd
                  AND fact.employee_id IS NOT NULL
            )
            SELECT
                employee.employee_id,
                definition.metric_code,
                definition.numerator_category_code,
                definition.denominator_code,
                COALESCE(SUM(fact.net_quantity) FILTER (
                    WHERE fact.numerator_metric_code = definition.metric_code
                ), 0) AS numerator_quantity,
                COALESCE(SUM(fact.net_quantity) FILTER (
                    WHERE definition.metric_code = ANY(fact.denominator_metric_codes)
                ), 0) AS denominator_quantity
            FROM rating_employees employee
            CROSS JOIN attach_rate_metric_definitions_v3 definition
            LEFT JOIN period_facts fact ON fact.employee_id = employee.employee_id
            GROUP BY
                employee.employee_id,
                definition.sort_order,
                definition.metric_code,
                definition.numerator_category_code,
                definition.denominator_code
            ORDER BY employee.employee_id, definition.sort_order
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public EmployeeAttachRateRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<EmployeeAttachRateAggregate> aggregate(
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
                EMPLOYEE_ATTACH_RATE_QUERY,
                parameters,
                (resultSet, rowNumber) -> new EmployeeAttachRateAggregate(
                        resultSet.getObject("employee_id", UUID.class),
                        resultSet.getString("metric_code"),
                        resultSet.getString("numerator_category_code"),
                        AttachDenominatorCode.valueOf(
                                resultSet.getString("denominator_code")
                        ),
                        resultSet.getBigDecimal("numerator_quantity"),
                        resultSet.getBigDecimal("denominator_quantity")
                )
        );
    }
}
