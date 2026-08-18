package com.storeanalytics.interpretation.snapshot;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class WeeklyEmployeeSalesSampleReader {

    private static final String QUERY = """
            SELECT
                document.employee_id,
                COUNT(DISTINCT document.id) AS completed_sales
            FROM sales_documents document
            JOIN sales_document_items item ON item.sales_document_id = document.id
            JOIN analytics_categories category ON category.id = item.analytics_category_id
            WHERE document.store_id = :storeId
              AND document.business_date BETWEEN :periodStart AND :periodEnd
              AND document.document_kind = 'SALE'
              AND document.source_document_type = 'sale'
              AND NOT document.is_deleted
              AND NOT item.is_deleted
              AND category.code <> 'EXCLUDE'
              AND document.employee_id IS NOT NULL
            GROUP BY document.employee_id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    WeeklyEmployeeSalesSampleReader(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    EmployeeSalesSampleFacts read(UUID storeId, StoreKpiPeriod period) {
        Map<UUID, Long> result = jdbcTemplate.query(
                QUERY,
                Map.of(
                        "storeId", storeId,
                        "periodStart", period.start(),
                        "periodEnd", period.end()
                ),
                (resultSet, rowNumber) -> Map.entry(
                        resultSet.getObject("employee_id", UUID.class),
                        resultSet.getLong("completed_sales")
                )
        ).stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                Map.Entry::getValue
        ));
        return new EmployeeSalesSampleFacts(result);
    }
}
