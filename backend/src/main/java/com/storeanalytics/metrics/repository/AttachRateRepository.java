package com.storeanalytics.metrics.repository;

import com.storeanalytics.product.model.AttachDenominatorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttachRateRepository {

    private static final String ATTACH_RATE_QUERY = """
            WITH period_documents AS (
                SELECT
                    document.id AS document_id,
                    document.document_kind,
                    CASE document.document_kind
                        WHEN 'SALE' THEN document.id
                        ELSE document.original_document_id
                    END AS context_document_id
                FROM sales_documents document
                WHERE document.store_id = :storeId
                  AND document.business_date BETWEEN :periodStart AND :periodEnd
                  AND NOT document.is_deleted
            ),
            period_items AS (
                SELECT
                    period_document.document_id,
                    period_document.context_document_id,
                    CASE period_document.document_kind WHEN 'SALE' THEN 1 ELSE -1 END AS sign,
                    item.analytics_category_id,
                    item.quantity,
                    item.condition_type_snapshot,
                    category.code AS category_code,
                    category.device_family,
                    category.counts_as_phone,
                    category.counts_as_device
                FROM period_documents period_document
                JOIN sales_document_items item
                  ON item.sales_document_id = period_document.document_id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE NOT item.is_deleted
                  AND category.code <> 'EXCLUDE'
            ),
            context_documents AS (
                SELECT DISTINCT context_document_id
                FROM period_documents
                WHERE context_document_id IS NOT NULL
            ),
            context_device_summary AS (
                SELECT
                    requested.context_document_id,
                    BOOL_OR(
                        category.counts_as_phone
                        AND category.device_family = 'IPHONE'
                    ) AS has_iphone,
                    BOOL_OR(
                        category.counts_as_phone
                        AND category.device_family = 'SAMSUNG'
                    ) AS has_samsung,
                    BOOL_OR(category.counts_as_phone) AS has_phone,
                    BOOL_OR(
                        category.counts_as_device
                        AND category.device_family = 'PODS_WATCH'
                    ) AS has_pods_watch,
                    BOOL_OR(
                        category.counts_as_device
                        AND category.device_family = 'IPAD_MAC'
                    ) AS has_ipad_mac,
                    BOOL_OR(
                        category.counts_as_device
                        AND item.condition_type_snapshot IN ('NEW', 'ASIS')
                    ) AS has_new_device,
                    BOOL_OR(
                        category.counts_as_device
                        AND item.condition_type_snapshot = 'USED'
                    ) AS has_used_device
                FROM context_documents requested
                JOIN sales_documents context_document
                  ON context_document.id = requested.context_document_id
                 AND context_document.store_id = :storeId
                 AND NOT context_document.is_deleted
                JOIN sales_document_items item
                  ON item.sales_document_id = context_document.id
                 AND NOT item.is_deleted
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE category.code <> 'EXCLUDE'
                GROUP BY requested.context_document_id
            ),
            original_rate_categories AS (
                SELECT
                    category.id AS numerator_category_id,
                    category.code AS numerator_category_code,
                    category.attach_denominator_code
                FROM analytics_categories category
                WHERE category.attach_denominator_code IS NOT NULL
                  AND category.requires_same_document_for_attach
                  AND category.code <> 'EXCLUDE'
            ),
            rate_definitions AS (
                SELECT
                    original.numerator_category_id,
                    original.numerator_category_code,
                    CASE
                        WHEN original.attach_denominator_code = 'MATCH_DEVICE_CONDITION'
                             AND expanded.denominator_code = 'NEW_DEVICE'
                            THEN original.numerator_category_code || '_NEW'
                        WHEN original.attach_denominator_code = 'MATCH_DEVICE_CONDITION'
                            THEN original.numerator_category_code || '_USED'
                        ELSE original.numerator_category_code
                    END AS metric_code,
                    expanded.denominator_code,
                    original.attach_denominator_code = 'MATCH_DEVICE_CONDITION'
                        AS match_device_condition
                FROM original_rate_categories original
                CROSS JOIN LATERAL (
                    SELECT original.attach_denominator_code AS denominator_code
                    WHERE original.attach_denominator_code <> 'MATCH_DEVICE_CONDITION'
                    UNION ALL
                    SELECT 'NEW_DEVICE'
                    WHERE original.attach_denominator_code = 'MATCH_DEVICE_CONDITION'
                    UNION ALL
                    SELECT 'USED_DEVICE'
                    WHERE original.attach_denominator_code = 'MATCH_DEVICE_CONDITION'
                ) expanded
            ),
            numerator_candidates AS (
                SELECT
                    definition.metric_code,
                    definition.numerator_category_code,
                    definition.denominator_code,
                    item.document_id,
                    item.sign,
                    item.quantity,
                    CASE
                        WHEN definition.match_device_condition
                             AND definition.denominator_code = 'NEW_DEVICE'
                            THEN COALESCE(context.has_new_device, false)
                                 AND NOT COALESCE(context.has_used_device, false)
                        WHEN definition.match_device_condition
                            THEN COALESCE(context.has_used_device, false)
                                 AND NOT COALESCE(context.has_new_device, false)
                        WHEN definition.denominator_code = 'IPHONE'
                            THEN COALESCE(context.has_iphone, false)
                        WHEN definition.denominator_code = 'SAMSUNG'
                            THEN COALESCE(context.has_samsung, false)
                        WHEN definition.denominator_code = 'PHONE'
                            THEN COALESCE(context.has_phone, false)
                        WHEN definition.denominator_code = 'PODS_WATCH'
                            THEN COALESCE(context.has_pods_watch, false)
                        WHEN definition.denominator_code = 'IPAD_MAC'
                            THEN COALESCE(context.has_ipad_mac, false)
                        WHEN definition.denominator_code = 'NEW_DEVICE'
                            THEN COALESCE(context.has_new_device, false)
                        WHEN definition.denominator_code = 'USED_DEVICE'
                            THEN COALESCE(context.has_used_device, false)
                        ELSE false
                    END AS attached
                FROM rate_definitions definition
                LEFT JOIN period_items item
                  ON item.analytics_category_id = definition.numerator_category_id
                LEFT JOIN context_device_summary context
                  ON context.context_document_id = item.context_document_id
            ),
            numerator_totals AS (
                SELECT
                    metric_code,
                    numerator_category_code,
                    denominator_code,
                    COALESCE(
                        SUM(sign * quantity) FILTER (
                            WHERE document_id IS NOT NULL AND attached
                        ),
                        0
                    ) AS numerator_quantity
                FROM numerator_candidates
                GROUP BY metric_code, numerator_category_code, denominator_code
            ),
            denominator_totals AS (
                SELECT
                    definition.metric_code,
                    COALESCE(SUM(item.sign * item.quantity), 0) AS denominator_quantity
                FROM rate_definitions definition
                LEFT JOIN period_items item ON CASE definition.denominator_code
                    WHEN 'IPHONE' THEN item.counts_as_phone
                        AND item.device_family = 'IPHONE'
                    WHEN 'SAMSUNG' THEN item.counts_as_phone
                        AND item.device_family = 'SAMSUNG'
                    WHEN 'PHONE' THEN item.counts_as_phone
                    WHEN 'PODS_WATCH' THEN item.counts_as_device
                        AND item.device_family = 'PODS_WATCH'
                    WHEN 'IPAD_MAC' THEN item.counts_as_device
                        AND item.device_family = 'IPAD_MAC'
                    WHEN 'NEW_DEVICE' THEN item.counts_as_device
                        AND item.condition_type_snapshot IN ('NEW', 'ASIS')
                    WHEN 'USED_DEVICE' THEN item.counts_as_device
                        AND item.condition_type_snapshot = 'USED'
                    ELSE false
                END
                GROUP BY definition.metric_code
            ),
            attach_quality_candidates AS (
                SELECT
                    item.document_id,
                    original.attach_denominator_code,
                    CASE original.attach_denominator_code
                        WHEN 'IPHONE' THEN COALESCE(context.has_iphone, false)
                        WHEN 'SAMSUNG' THEN COALESCE(context.has_samsung, false)
                        WHEN 'PHONE' THEN COALESCE(context.has_phone, false)
                        WHEN 'PODS_WATCH' THEN COALESCE(context.has_pods_watch, false)
                        WHEN 'IPAD_MAC' THEN COALESCE(context.has_ipad_mac, false)
                        WHEN 'NEW_DEVICE' THEN COALESCE(context.has_new_device, false)
                        WHEN 'USED_DEVICE' THEN COALESCE(context.has_used_device, false)
                        WHEN 'MATCH_DEVICE_CONDITION' THEN
                            COALESCE(context.has_new_device, false)
                            <> COALESCE(context.has_used_device, false)
                        ELSE false
                    END AS attached,
                    original.attach_denominator_code = 'MATCH_DEVICE_CONDITION'
                        AND COALESCE(context.has_new_device, false)
                        AND COALESCE(context.has_used_device, false) AS ambiguous_warranty
                FROM period_items item
                JOIN original_rate_categories original
                  ON original.numerator_category_id = item.analytics_category_id
                LEFT JOIN context_device_summary context
                  ON context.context_document_id = item.context_document_id
            ),
            quality AS (
                SELECT
                    (
                        SELECT COUNT(*)
                        FROM attach_quality_candidates candidate
                        WHERE NOT candidate.attached
                          AND NOT candidate.ambiguous_warranty
                    ) AS unmatched_numerator_item_count,
                    (
                        SELECT COUNT(*)
                        FROM attach_quality_candidates candidate
                        WHERE candidate.ambiguous_warranty
                    ) AS ambiguous_warranty_item_count,
                    (
                        SELECT COUNT(*)
                        FROM period_items item
                        WHERE item.counts_as_device
                          AND item.condition_type_snapshot NOT IN ('NEW', 'ASIS', 'USED')
                    ) AS unknown_device_condition_item_count
            )
            SELECT
                numerator.metric_code,
                numerator.numerator_category_code,
                numerator.denominator_code,
                numerator.numerator_quantity,
                denominator.denominator_quantity,
                quality.unmatched_numerator_item_count,
                quality.ambiguous_warranty_item_count,
                quality.unknown_device_condition_item_count
            FROM numerator_totals numerator
            JOIN denominator_totals denominator USING (metric_code)
            CROSS JOIN quality
            ORDER BY numerator.metric_code
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public AttachRateRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<AttachRateAggregate> aggregate(
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
                ATTACH_RATE_QUERY,
                parameters,
                (resultSet, rowNumber) -> new AttachRateAggregate(
                        resultSet.getString("metric_code"),
                        resultSet.getString("numerator_category_code"),
                        AttachDenominatorCode.valueOf(resultSet.getString("denominator_code")),
                        resultSet.getBigDecimal("numerator_quantity"),
                        resultSet.getBigDecimal("denominator_quantity"),
                        resultSet.getLong("unmatched_numerator_item_count"),
                        resultSet.getLong("ambiguous_warranty_item_count"),
                        resultSet.getLong("unknown_device_condition_item_count")
                )
        );
    }
}
