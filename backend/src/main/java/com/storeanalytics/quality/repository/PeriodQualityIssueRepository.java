package com.storeanalytics.quality.repository;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PeriodQualityIssueRepository {

    private static final String COUNT_OPEN_CONSISTENCY_ISSUES = """
            WITH target_store AS (
                SELECT id, timezone
                FROM stores
                WHERE id = :storeId
            ),
            period_documents AS (
                SELECT document.id,
                       document.connection_id,
                       document.external_id,
                       document.document_kind
                FROM sales_documents document
                JOIN target_store store ON store.id = document.store_id
                WHERE document.business_date BETWEEN :periodStart AND :periodEnd
                  AND NOT document.is_deleted
            ),
            period_entities AS (
                SELECT
                    CASE document.document_kind
                        WHEN 'SALE' THEN 'SALE_DOCUMENT'
                        ELSE 'RETURN_DOCUMENT'
                    END AS entity_type,
                    document.connection_id::text || ':' || document.external_id AS entity_id
                FROM period_documents document

                UNION ALL

                SELECT
                    CASE document.document_kind
                        WHEN 'SALE' THEN 'SALE_ITEM'
                        ELSE 'RETURN_ITEM'
                    END AS entity_type,
                    document.connection_id::text || ':' || item.external_id AS entity_id
                FROM period_documents document
                JOIN sales_document_items item ON item.sales_document_id = document.id
                WHERE NOT item.is_deleted
            ),
            period_raw_returns AS (
                SELECT DISTINCT
                    raw.connection_id::text || ':' || raw.external_id AS entity_id
                FROM raw_record_versions raw
                JOIN target_store store ON store.id = raw.store_id
                WHERE raw.entity_type = 'RETURN_DOCUMENT'
                  AND (raw.source_updated_at AT TIME ZONE store.timezone)::date
                        BETWEEN :periodStart AND :periodEnd
            )
            SELECT COUNT(*)
            FROM data_quality_issues issue
            JOIN target_store store ON store.id = issue.store_id
            WHERE issue.status = 'OPEN'
              AND issue.issue_code NOT IN (
                  'UNMAPPED_PRODUCT',
                  'ZERO_UNEXPECTED_COST',
                  'MISSING_COST',
                  'RETURN_ZERO_UNEXPECTED_COST',
                  'RETURN_MISSING_COST'
              )
              AND (
                  EXISTS (
                      SELECT 1
                      FROM period_entities entity
                      WHERE entity.entity_type = issue.entity_type
                        AND entity.entity_id = issue.entity_id
                  )
                  OR (
                      issue.entity_type = 'RETURN_DOCUMENT'
                      AND issue.issue_code = 'RETURN_ORIGINAL_DOCUMENT_MISSING'
                      AND EXISTS (
                          SELECT 1
                          FROM period_raw_returns raw
                          WHERE raw.entity_id = issue.entity_id
                      )
                  )
              )
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PeriodQualityIssueRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countOpenConsistencyIssues(
            UUID storeId,
            LocalDate periodStart,
            LocalDate periodEnd
    ) {
        Map<String, Object> parameters = Map.of(
                "storeId", storeId,
                "periodStart", periodStart,
                "periodEnd", periodEnd
        );
        Long result = jdbcTemplate.queryForObject(
                COUNT_OPEN_CONSISTENCY_ISSUES,
                parameters,
                Long.class
        );
        return result == null ? 0 : result;
    }
}
