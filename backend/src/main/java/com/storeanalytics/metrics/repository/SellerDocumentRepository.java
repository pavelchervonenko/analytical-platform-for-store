package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SellerDocumentRepository {

    private static final String QUERY = AnalyticalDocumentSql.PERIOD_DOCUMENTS_CTE + """
            , documents AS (
                SELECT document.id, document.analytical_employee_id AS employee_id, document.document_kind,
                       document.source_document_type
                FROM analytical_documents document
                WHERE document.store_id = :storeId
                  AND document.business_date BETWEEN :periodStart AND :periodEnd
                  AND NOT document.is_deleted
                  AND document.analytical_employee_id IN (:employeeIds)
            ),
            amounts AS (
                SELECT document.id, SUM(item.net_amount) AS amount
                FROM documents document
                JOIN sales_document_items item ON item.sales_document_id = document.id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE NOT item.is_deleted AND category.code <> 'EXCLUDE'
                GROUP BY document.id
            )
            SELECT document.employee_id,
                   COALESCE(SUM(amount.amount) FILTER (
                       WHERE document.document_kind = 'SALE'), 0) AS sales_revenue,
                   COALESCE(SUM(amount.amount) FILTER (
                       WHERE document.document_kind = 'RETURN'), 0) AS return_revenue,
                   COUNT(*) FILTER (WHERE document.document_kind = 'SALE') AS sale_count,
                   COUNT(*) FILTER (WHERE document.document_kind = 'RETURN') AS return_count,
                   COUNT(*) FILTER (
                       WHERE document.document_kind = 'SALE'
                         AND document.source_document_type = 'sale'
                         AND amount.id IS NOT NULL) AS completed_count
            FROM documents document
            LEFT JOIN amounts amount ON amount.id = document.id
            GROUP BY document.employee_id
            ORDER BY document.employee_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SellerDocumentRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SellerDocumentAggregate> read(SellerCohortSnapshot cohort, StoreKpiPeriod period) {
        if (cohort.employeeIds().isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(QUERY, Map.of(
                "storeId", cohort.storeId(), "employeeIds", cohort.employeeIds(),
                "periodStart", period.start(), "periodEnd", period.end()
        ), (row, index) -> new SellerDocumentAggregate(
                row.getObject("employee_id", java.util.UUID.class),
                row.getBigDecimal("sales_revenue"), row.getBigDecimal("return_revenue"),
                row.getLong("sale_count"), row.getLong("return_count"),
                row.getLong("completed_count")
        ));
    }
}
