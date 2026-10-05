package com.storeanalytics.metrics.repository;

import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerHistoricalFactsUnavailableException;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.warranty.AttachAttributionPolicy;
import com.storeanalytics.product.model.AttachDenominatorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Aggregates raw seller quantities using the existing classifier, before any clamp or division. */
@Repository
public class SellerAttachRateRepository {

    private static final String TEMPORAL_ELIGIBILITY = """
            EXISTS (
                SELECT 1 FROM store_seller_membership_state state
                JOIN seller_membership_history history ON history.store_id = state.store_id
                WHERE state.store_id = fact.store_id AND history.employee_id = fact.employee_id
                  AND fact.membership_at >= state.authoritative_from
                  AND tstzrange(history.valid_from, history.valid_to, '[)') @> fact.membership_at
                  AND history.employee_active AND history.assignment_active AND history.participates_in_ranking
            )
            """;

    private static final String UNKNOWN_HISTORY = """
            SELECT EXISTS (
                SELECT 1 FROM seller_attach_item_facts_v1 fact
                WHERE fact.store_id = :storeId AND fact.business_date BETWEEN :periodStart AND :periodEnd
                  AND ((fact.employee_id IS NULL AND fact.membership_document_kind = 'RETURN')
                       OR (fact.employee_id IS NOT NULL AND NOT EXISTS (
                           SELECT 1 FROM store_seller_membership_state state
                           JOIN seller_membership_history history ON history.store_id = state.store_id
                           WHERE state.store_id = fact.store_id AND history.employee_id = fact.employee_id
                             AND fact.membership_at >= state.authoritative_from
                             AND tstzrange(history.valid_from, history.valid_to, '[)') @> fact.membership_at
                       )))
            )
            """;

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
        String selectedFilter = filter;
        if (policy.enabled()) {
            return storeQuality.readWith(cohort.storeId(), period.start(), period.end(),
                    quality -> readRates(parameters, selectedFilter, quality));
        }
        return readRates(parameters, selectedFilter, List.of());
    }

    /** Must join the historical financial reader's RR transaction; never falls back to current roster/v3. */
    public List<AttachRateAggregate> readHistorical(SellerCohortSnapshot cohort, StoreKpiPeriod period) {
        if (!Integer.valueOf(TransactionDefinition.ISOLATION_REPEATABLE_READ).equals(
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Historical attach requires the caller's consistent transaction");
        }
        if (!policy.enabled()) {
            throw new SellerHistoricalFactsUnavailableException("HISTORICAL_ATTACH_REQUIRES_V4_POLICY");
        }
        Map<String, Object> parameters = new HashMap<>(Map.of(
                "storeId", cohort.storeId(), "periodStart", period.start(), "periodEnd", period.end()));
        String filter = "false";
        String outsideCohort = "true";
        if (!cohort.employeeIds().isEmpty()) {
            parameters.put("employeeIds", cohort.employeeIds());
            filter = "fact.employee_id IN (:employeeIds) AND " + TEMPORAL_ELIGIBILITY;
            outsideCohort = "fact.employee_id NOT IN (:employeeIds)";
        }
        String selectedFilter = filter;
        String cohortGuard = """
                SELECT EXISTS (SELECT 1 FROM seller_attach_item_facts_v1 fact
                    WHERE fact.store_id = :storeId AND fact.business_date BETWEEN :periodStart AND :periodEnd
                      AND (%s) AND (%s))
                """.formatted(TEMPORAL_ELIGIBILITY, outsideCohort);
        return storeQuality.readWith(cohort.storeId(), period.start(), period.end(), quality -> {
            if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(UNKNOWN_HISTORY, parameters, Boolean.class))) {
                throw new SellerHistoricalFactsUnavailableException("ATTACH_MEMBERSHIP_OR_AUTHOR_UNKNOWN");
            }
            if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(cohortGuard, parameters, Boolean.class))) {
                throw new SellerHistoricalFactsUnavailableException("ATTACH_COHORT_NOT_COVERED");
            }
            return readRates(parameters, selectedFilter, quality,
                    QUERY.replace("attach_rate_item_facts_v3_catalog", "seller_attach_item_facts_v1"));
        });
    }

    private List<AttachRateAggregate> readRates(Map<String, Object> parameters, String filter,
                                               List<AttachAttributionQuality> qualityRows) {
        String query = policy.enabled()
                ? QUERY.replace("attach_rate_item_facts_v3", "attach_rate_item_facts_v4") : QUERY;
        return readRates(parameters, filter, qualityRows, query);
    }

    private List<AttachRateAggregate> readRates(Map<String, Object> parameters, String filter,
            List<AttachAttributionQuality> qualityRows, String query) {
        Map<String, AttachAttributionQuality> quality = new HashMap<>();
        qualityRows.forEach(value -> quality.put(value.metricCode(), value));
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

    public String historicalFormulaVersion() {
        return "attach-rate-v4-historical-membership-v1";
    }
}
