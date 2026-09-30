package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.product.model.AttachDenominatorCode;
import com.storeanalytics.metrics.warranty.AttachAttributionPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Aggregates raw seller quantities using the existing classifier, before any clamp or division. */
@Repository
public class SellerAttachRateRepository {

    private static final String QUERY = """
            WITH facts AS (
                SELECT fact.* FROM attach_rate_item_facts_v3_catalog fact
                WHERE fact.store_id = :storeId
                  AND fact.business_date BETWEEN :periodStart AND :periodEnd
                  AND %s
            ),
            quality AS (
                SELECT COUNT(*) FILTER (WHERE classification_issue_code =
                           'IPAD_ACCESSORY_TARGET_UNRESOLVED') AS unmatched,
                       COUNT(*) FILTER (WHERE classification_issue_code =
                           'WARRANTY_TARGET_UNRESOLVED') AS ambiguous,
                       COUNT(*) FILTER (WHERE classification_issue_code =
                           'DEVICE_CONDITION_UNKNOWN') AS unknown_condition,
                       COUNT(*) FILTER (WHERE classification_issue_code = 'PODS_WATCH_SUBTYPE_UNRESOLVED')
                           AS catalog_subtype_unresolved_count
                FROM facts
            )
            SELECT definition.metric_code, definition.numerator_category_code,
                   definition.denominator_code,
                   COALESCE(SUM(fact.net_quantity) FILTER (
                       WHERE definition.metric_code = ANY(fact.numerator_metric_codes)), 0) AS numerator,
                   COALESCE(SUM(fact.net_quantity) FILTER (
                       WHERE definition.metric_code = ANY(fact.denominator_metric_codes)), 0) AS denominator,
                   quality.unmatched, quality.ambiguous, quality.unknown_condition,
                   quality.catalog_subtype_unresolved_count,
                   COALESCE(bool_or(catalog_attach_metric_uncertain(
                       fact.classification_issue_code, definition.metric_code)), false) AS catalog_role_preliminary
            FROM attach_rate_metric_definitions_catalog definition
            LEFT JOIN facts fact ON true
            CROSS JOIN quality
            GROUP BY definition.sort_order, definition.metric_code,
                     definition.numerator_category_code, definition.denominator_code,
                     quality.unmatched, quality.ambiguous, quality.unknown_condition,
                   quality.catalog_subtype_unresolved_count
            ORDER BY definition.sort_order
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AttachAttributionPolicy policy;
    private final AttachAttributionQualityRepository storeQuality;

    public SellerAttachRateRepository(NamedParameterJdbcTemplate jdbcTemplate,
                                     AttachAttributionPolicy policy, AttachAttributionQualityRepository storeQuality) {
        this.jdbcTemplate = jdbcTemplate;
        this.policy = policy;
        this.storeQuality = storeQuality;
    }

    public List<AttachRateAggregate> read(SellerCohortSnapshot cohort, StoreKpiPeriod period) {
        Map<String, Object> parameters = new HashMap<>(Map.of(
                "storeId", cohort.storeId(), "periodStart", period.start(), "periodEnd", period.end()
        ));
        String filter = "false";
        if (!cohort.employeeIds().isEmpty()) {
            filter = "fact.employee_id IN (:employeeIds)";
            parameters.put("employeeIds", cohort.employeeIds());
        }
        Map<String, AttachAttributionQuality> quality = new HashMap<>();
        if (policy.enabled()) {
            storeQuality.read(cohort.storeId(), period.start(), period.end())
                    .forEach(value -> quality.put(value.metricCode(), value));
        }
        String query = policy.enabled()
                ? QUERY.replace("attach_rate_item_facts_v3", "attach_rate_item_facts_v4") : QUERY;
        return jdbcTemplate.query(query.formatted(filter), parameters,
                (row, index) -> new AttachRateAggregate(
                        row.getString("metric_code"), row.getString("numerator_category_code"),
                        AttachDenominatorCode.valueOf(row.getString("denominator_code")),
                        row.getBigDecimal("numerator"), row.getBigDecimal("denominator"),
                        row.getLong("unmatched"), row.getLong("ambiguous"), row.getLong("unknown_condition"),
                        java.util.Set.of("ACCESSORY_AIRPODS", "ACCESSORY_APPLE_WATCH")
                                .contains(row.getString("metric_code"))
                                && row.getLong("catalog_subtype_unresolved_count") > 0
                                || row.getBoolean("catalog_role_preliminary"), 0, 0
                )).stream().map(value -> quality.containsKey(value.metricCode())
                        ? value.withPotentialStoreAttributionRisk(quality.get(value.metricCode())) : value).toList();
    }

    public String formulaVersion() {
        return policy.enabled() ? "attach-rate-v4" : "attach-rate-v3";
    }
}
