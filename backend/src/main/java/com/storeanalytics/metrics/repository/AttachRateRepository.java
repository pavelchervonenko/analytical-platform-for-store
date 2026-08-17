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
            WITH period_facts AS (
                SELECT fact.*
                FROM attach_rate_item_facts_v3 fact
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
                    ) AS unknown_device_condition_item_count
                FROM period_facts
            )
            SELECT
                definition.metric_code,
                definition.numerator_category_code,
                definition.denominator_code,
                COALESCE(SUM(fact.net_quantity) FILTER (
                    WHERE fact.numerator_metric_code = definition.metric_code
                ), 0) AS numerator_quantity,
                COALESCE(SUM(fact.net_quantity) FILTER (
                    WHERE definition.metric_code = ANY(fact.denominator_metric_codes)
                ), 0) AS denominator_quantity,
                quality.unmatched_numerator_item_count,
                quality.ambiguous_warranty_item_count,
                quality.unknown_device_condition_item_count
            FROM attach_rate_metric_definitions_v3 definition
            LEFT JOIN period_facts fact ON true
            CROSS JOIN quality
            GROUP BY
                definition.sort_order,
                definition.metric_code,
                definition.numerator_category_code,
                definition.denominator_code,
                quality.unmatched_numerator_item_count,
                quality.ambiguous_warranty_item_count,
                quality.unknown_device_condition_item_count
            ORDER BY definition.sort_order
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
                        AttachDenominatorCode.valueOf(
                                resultSet.getString("denominator_code")
                        ),
                        resultSet.getBigDecimal("numerator_quantity"),
                        resultSet.getBigDecimal("denominator_quantity"),
                        resultSet.getLong("unmatched_numerator_item_count"),
                        resultSet.getLong("ambiguous_warranty_item_count"),
                        resultSet.getLong("unknown_device_condition_item_count")
                )
        );
    }
}
