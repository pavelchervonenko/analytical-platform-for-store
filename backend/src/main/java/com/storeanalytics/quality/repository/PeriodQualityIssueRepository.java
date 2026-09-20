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
                SELECT id
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
            return_cash_evidence AS (
                SELECT document.connection_id::text || ':' || document.external_id AS entity_id,
                       payment.active_payment_total,
                       raw.source_cash_total
                FROM period_documents document
                CROSS JOIN LATERAL (
                    SELECT COALESCE(SUM(payment.amount), 0) AS active_payment_total
                    FROM sales_payments payment
                    WHERE payment.sales_document_id = document.id
                      AND NOT payment.is_deleted
                ) payment
                LEFT JOIN LATERAL (
                    SELECT CASE
                        WHEN jsonb_typeof(version.payload #> '{detail,cash}') = 'object'
                         AND COALESCE(
                                 version.payload #>> '{detail,cash,money}',
                                 '0'
                             ) ~ '^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$'
                         AND COALESCE(
                                 version.payload #>> '{detail,cash,bank}',
                                 '0'
                             ) ~ '^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$'
                         AND COALESCE(
                                 version.payload #>> '{detail,cash,invoice}',
                                 '0'
                             ) ~ '^[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?$'
                        THEN COALESCE(
                                 version.payload #>> '{detail,cash,money}',
                                 '0'
                             )::numeric
                           + COALESCE(
                                 version.payload #>> '{detail,cash,bank}',
                                 '0'
                             )::numeric
                           + COALESCE(
                                 version.payload #>> '{detail,cash,invoice}',
                                 '0'
                             )::numeric
                        ELSE NULL
                    END AS source_cash_total
                    FROM raw_record_versions version
                    WHERE version.connection_id = document.connection_id
                      AND version.store_id = :storeId
                      AND version.source_system = 'LIVESKLAD'
                      AND version.entity_type = 'RETURN_DOCUMENT'
                      AND version.external_id = document.external_id
                    ORDER BY version.first_seen_at DESC, version.id DESC
                    LIMIT 1
                ) raw ON true
                WHERE document.document_kind = 'RETURN'
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
                  'RETURN_MISSING_COST',
                  'RETURN_ORIGINAL_DOCUMENT_MISSING',
                  'RETURN_ORIGINAL_ITEM_MISSING',
                  'SALE_PAYMENT_MISMATCH',
                  'RETURN_PAYMENT_MISMATCH'
              )
              AND EXISTS (
                  SELECT 1
                  FROM period_entities entity
                  WHERE entity.entity_type = issue.entity_type
                    AND entity.entity_id = issue.entity_id
              )
              AND NOT (
                  issue.issue_code = 'RETURN_CASH_TRANSACTION_MISMATCH'
                  AND EXISTS (
                      SELECT 1
                      FROM return_cash_evidence evidence
                      WHERE evidence.entity_id = issue.entity_id
                        AND evidence.source_cash_total IS NOT NULL
                        AND evidence.active_payment_total = evidence.source_cash_total
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
