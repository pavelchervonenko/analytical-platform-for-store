package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.service.SellerReturnAttributionQuality;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Counts uncertain return attribution once per document, without assigning it to a seller. */
@Repository
public class SellerReturnAttributionRepository {

    private static final String QUERY = """
            SELECT COUNT(*) FILTER (
                       WHERE original.id IS NULL OR original.is_deleted
                          OR original.store_id <> returned.store_id
                          OR original.document_kind <> 'SALE') AS orphan_count,
                   COUNT(*) FILTER (
                       WHERE original.id IS NOT NULL AND NOT original.is_deleted
                         AND original.store_id = returned.store_id
                         AND original.document_kind = 'SALE'
                         AND original.employee_id IS NULL) AS unattributed_original_count
            FROM sales_documents returned
            LEFT JOIN sales_documents original ON original.id = returned.original_document_id
            WHERE returned.store_id = ?
              AND returned.business_date BETWEEN ? AND ?
              AND returned.document_kind = 'RETURN'
              AND NOT returned.is_deleted
            """;

    private final JdbcTemplate jdbc;

    public SellerReturnAttributionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SellerReturnAttributionQuality read(UUID storeId, StoreKpiPeriod period) {
        return jdbc.queryForObject(QUERY, (row, index) -> new SellerReturnAttributionQuality(
                row.getLong("orphan_count"), row.getLong("unattributed_original_count")),
                storeId, period.start(), period.end());
    }
}
