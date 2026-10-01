package com.storeanalytics.metrics.repository;

import com.storeanalytics.product.model.AttachDenominatorCode;
import com.storeanalytics.metrics.warranty.AttachAttributionPolicy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttachRateRepository {

    private static final String ATTACH_RATE_QUERY = """
            WITH period_facts AS (
                SELECT fact.*
                FROM attach_rate_item_facts_v3_catalog fact
                WHERE fact.store_id = :storeId
                  AND fact.business_date BETWEEN :periodStart AND :periodEnd
            ),
            quality AS (
                SELECT
                    COUNT(*) FILTER (
                        WHERE classification_issue_code =
                            'IPAD_ACCESSORY_TARGET_UNRESOLVED'
                    ) AS unmatched_numerator_item_count,
                    COUNT(*) FILTER (
                        WHERE classification_issue_code =
                            'WARRANTY_TARGET_UNRESOLVED'
                    ) AS ambiguous_warranty_item_count,
                    COUNT(*) FILTER (
                        WHERE classification_issue_code =
                            'DEVICE_CONDITION_UNKNOWN'
                    ) AS unknown_device_condition_item_count,
                    COUNT(*) FILTER (WHERE classification_issue_code = 'PODS_WATCH_SUBTYPE_UNRESOLVED')
                        AS catalog_subtype_unresolved_count
                FROM period_facts
            )
            SELECT
                definition.metric_code,
                definition.numerator_category_code,
                definition.denominator_code,
                COALESCE(SUM(fact.net_quantity) FILTER (
                    WHERE definition.metric_code = ANY(fact.numerator_metric_codes)
                ), 0) AS numerator_quantity,
                COALESCE(SUM(fact.net_quantity) FILTER (
                    WHERE definition.metric_code = ANY(fact.denominator_metric_codes)
                ), 0) AS denominator_quantity,
                quality.unmatched_numerator_item_count,
                quality.ambiguous_warranty_item_count,
                quality.unknown_device_condition_item_count,
                quality.catalog_subtype_unresolved_count,
                COALESCE(bool_or(catalog_attach_metric_uncertain(
                    fact.classification_issue_code, definition.metric_code)), false) AS catalog_role_preliminary
            FROM attach_rate_metric_definitions_catalog definition
            LEFT JOIN period_facts fact ON true
            CROSS JOIN quality
            GROUP BY
                definition.sort_order,
                definition.metric_code,
                definition.numerator_category_code,
                definition.denominator_code,
                quality.unmatched_numerator_item_count,
                quality.ambiguous_warranty_item_count,
                quality.unknown_device_condition_item_count,
                quality.catalog_subtype_unresolved_count
            ORDER BY definition.sort_order
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AttachAttributionPolicy policy;

    public AttachRateRepository(NamedParameterJdbcTemplate jdbcTemplate, AttachAttributionPolicy policy) {
        this.jdbcTemplate = jdbcTemplate;
        this.policy = policy;
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
                query(),
                parameters,
                (resultSet, rowNumber) -> new AttachRateAggregate(
                        resultSet.getString("metric_code"),
                        resultSet.getString("numerator_category_code"),
                        AttachDenominatorCode.valueOf(
                                resultSet.getString("denominator_code")
                        ),
                        resultSet.getBigDecimal("numerator_quantity"),
                        resultSet.getBigDecimal("denominator_quantity"),
                        resultSet.getLong("unmatched_numerator_item_count"),
                        resultSet.getLong(policy.enabled()
                                ? "pending_warranty_count" : "ambiguous_warranty_item_count"),
                        resultSet.getLong("unknown_device_condition_item_count"),
                        (policy.enabled() && resultSet.getString("metric_code").startsWith("WARRANTY_GENERIC_")
                                && resultSet.getLong("pending_warranty_count") > 0)
                                || (java.util.Set.of("ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH")
                                        .contains(resultSet.getString("metric_code"))
                                    && resultSet.getLong("catalog_subtype_unresolved_count") > 0)
                                || resultSet.getBoolean("catalog_role_preliminary"),
                        policy.enabled() ? resultSet.getLong("unassigned_return_item_count") : 0,
                        policy.enabled() ? resultSet.getLong("unassigned_metric_return_item_count") : 0
                )
        );
    }
    public boolean attributionEnabled() {
        return policy.enabled();
    }

    private String query() {
        if (!policy.enabled()) {
            return ATTACH_RATE_QUERY;
        }
        String base = ATTACH_RATE_QUERY.replace("attach_rate_item_facts_v3", "attach_rate_item_facts_v4");
        return "SELECT base.*, " + """
                (SELECT count(*) FROM warranty_attach_cases w
                 WHERE w.store_id = :storeId AND w.state IN ('CONFLICT','DEFERRED') AND w.pending_quantity > 0
                   AND (w.document_kind = 'RETURN' OR w.type_count = 0 OR w.decision_id IS NOT NULL
                        OR w.business_date BETWEEN :periodStart AND :periodEnd)) AS pending_warranty_count,
                (SELECT count(*) FROM attach_rate_ordinary_item_facts_v4_catalog_with_reviews f
                 WHERE f.store_id = :storeId AND f.business_date BETWEEN :periodStart AND :periodEnd
                   AND f.net_quantity < 0 AND f.employee_id IS NULL
                   AND (f.numerator_metric_code IS NOT NULL OR cardinality(f.denominator_metric_codes) > 0))
                    AS unassigned_return_item_count,
                (SELECT count(*) FROM attach_rate_ordinary_item_facts_v4_catalog_with_reviews f
                 WHERE f.store_id = :storeId AND f.business_date BETWEEN :periodStart AND :periodEnd
                   AND f.net_quantity < 0 AND f.employee_id IS NULL
                   AND (base.metric_code = ANY(f.numerator_metric_codes)
                        OR base.metric_code = ANY(f.denominator_metric_codes))) AS unassigned_metric_return_item_count
                FROM (
                """ + base + ") base";
    }
}
