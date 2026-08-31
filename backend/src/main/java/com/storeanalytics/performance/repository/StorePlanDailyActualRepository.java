package com.storeanalytics.performance.repository;

import com.storeanalytics.metrics.service.OverviewMetricScope;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StorePlanDailyActualRepository {

    private static final String DAILY_ACTUAL_QUERY = """
            SELECT
                document.business_date,
                COALESCE(SUM(
                    CASE document.document_kind WHEN 'SALE' THEN item.net_amount
                                                ELSE -item.net_amount END
                ), 0) AS revenue_amount,
                COALESCE(SUM(
                    CASE WHEN category.category_kind = 'ACCESSORY'
                         THEN CASE document.document_kind WHEN 'SALE' THEN item.net_amount
                                                               ELSE -item.net_amount END
                         ELSE 0 END
                ), 0) AS accessory_amount,
                COALESCE(SUM(
                    CASE WHEN category.category_kind IN ('SERVICE', 'WARRANTY', 'PROTECTION')
                         THEN CASE document.document_kind WHEN 'SALE' THEN item.net_amount
                                                               ELSE -item.net_amount END
                         ELSE 0 END
                ), 0) AS service_amount
            FROM sales_documents document
            JOIN sales_document_items item ON item.sales_document_id = document.id
            JOIN analytics_categories category ON category.id = item.analytics_category_id
            WHERE document.store_id = :storeId
              AND document.business_date BETWEEN :periodStart AND :periodEnd
              AND NOT document.is_deleted
              AND NOT item.is_deleted
              AND category.code <> 'EXCLUDE'
              AND (
                  :scope = 'STORE'
                  OR EXISTS (
                      SELECT 1
                      FROM employee_store_assignments assignment
                      JOIN employees employee ON employee.id = assignment.employee_id
                      WHERE assignment.store_id = document.store_id
                        AND assignment.employee_id = document.employee_id
                        AND assignment.is_active
                        AND assignment.participates_in_ranking
                        AND employee.is_active
                  )
              )
            GROUP BY document.business_date
            ORDER BY document.business_date
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public StorePlanDailyActualRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<StorePlanDailyActual> aggregate(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        return aggregate(storeId, periodStart, periodEnd, OverviewMetricScope.STORE);
    }

    public List<StorePlanDailyActual> aggregate(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd,
            OverviewMetricScope scope
    ) {
        return jdbcTemplate.query(
                DAILY_ACTUAL_QUERY,
                Map.of(
                        "storeId", storeId,
                        "periodStart", periodStart,
                        "periodEnd", periodEnd,
                        "scope", scope.name()
                ),
                (resultSet, rowNumber) -> new StorePlanDailyActual(
                        resultSet.getObject("business_date", LocalDate.class),
                        resultSet.getBigDecimal("revenue_amount"),
                        resultSet.getBigDecimal("accessory_amount"),
                        resultSet.getBigDecimal("service_amount")
                )
        );
    }
}
