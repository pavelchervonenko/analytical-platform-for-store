package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.service.SellerReturnAttributionQuality;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.util.UUID;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Counts uncertain return attribution once per document, without assigning it to a seller. */
@Repository
public class SellerReturnAttributionRepository {

    private static final String QUERY = AnalyticalDocumentSql.PERIOD_DOCUMENTS_CTE + """
            SELECT COUNT(*) FILTER (
                       WHERE returned.attach_source_employee_external_id IS NULL) AS missing_employee_count,
                   COUNT(*) FILTER (
                       WHERE returned.attach_source_employee_external_id IS NOT NULL
                         AND returned.analytical_employee_id IS NULL) AS unresolved_employee_count
            FROM analytical_documents returned
            WHERE returned.document_kind = 'RETURN'
              AND EXISTS (
                  SELECT 1 FROM sales_document_items item
                  JOIN analytics_categories category ON category.id = item.analytics_category_id
                  WHERE item.sales_document_id = returned.id
                    AND NOT item.is_deleted AND category.code <> 'EXCLUDE'
              )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SellerReturnAttributionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SellerReturnAttributionQuality read(UUID storeId, StoreKpiPeriod period) {
        return jdbc.queryForObject(QUERY, Map.of("storeId", storeId,
                "periodStart", period.start(), "periodEnd", period.end()),
                (row, index) -> new SellerReturnAttributionQuality(
                        row.getLong("missing_employee_count"), row.getLong("unresolved_employee_count")));
    }
}
