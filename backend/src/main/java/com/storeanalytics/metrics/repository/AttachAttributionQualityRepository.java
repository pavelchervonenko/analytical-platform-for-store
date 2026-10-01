package com.storeanalytics.metrics.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Reads the existing v4 quality predicates without evaluating the whole-store attach quantities. */
@Repository
public class AttachAttributionQualityRepository {

    private static final String QUERY = """
            WITH unassigned_facts AS MATERIALIZED (
                SELECT fact.classification_issue_code, fact.numerator_metric_code,
                       fact.numerator_metric_codes, fact.denominator_metric_codes
                FROM attach_rate_ordinary_item_facts_v4_catalog_with_reviews fact
                WHERE fact.store_id = :storeId AND fact.business_date BETWEEN :periodStart AND :periodEnd
                  AND fact.net_quantity < 0 AND fact.employee_id IS NULL
            ), pending_roles AS (
                SELECT classification_issue_code FROM unassigned_facts
                WHERE classification_issue_code LIKE 'CATALOG_ROLE_REVIEW_%'
            ), unassigned_returns AS MATERIALIZED (
                SELECT numerator_metric_code, numerator_metric_codes, denominator_metric_codes
                FROM unassigned_facts
                WHERE numerator_metric_code IS NOT NULL OR cardinality(denominator_metric_codes) > 0
            ), pending_warranties AS (
                SELECT count(*) AS item_count FROM warranty_attach_cases warranty
                WHERE warranty.store_id = :storeId AND warranty.state IN ('CONFLICT', 'DEFERRED')
                  AND warranty.pending_quantity > 0
                  AND (warranty.document_kind = 'RETURN' OR warranty.type_count = 0
                       OR warranty.decision_id IS NOT NULL
                       OR warranty.business_date BETWEEN :periodStart AND :periodEnd)
            )
            SELECT definition.metric_code, pending.item_count AS pending_warranty_count,
                   EXISTS (SELECT 1 FROM pending_roles p
                       WHERE catalog_attach_metric_uncertain(p.classification_issue_code, definition.metric_code))
                       AS pending_catalog_role,
                   (SELECT count(*) FROM unassigned_returns) AS unassigned_return_count,
                   (SELECT count(*) FROM unassigned_returns fact
                    WHERE definition.metric_code = ANY(fact.numerator_metric_codes)
                       OR definition.metric_code = ANY(fact.denominator_metric_codes)) AS unassigned_metric_return_count
            FROM attach_rate_metric_definitions_catalog definition CROSS JOIN pending_warranties pending
            ORDER BY definition.sort_order
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public AttachAttributionQualityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AttachAttributionQuality> read(UUID storeId, LocalDate start, LocalDate end) {
        return readWith(storeId, start, end, Function.identity());
    }

    /** Keeps the related seller aggregate in the same bounded, transaction-local query setting. */
    @Transactional(readOnly = true)
    public <T> T readWith(UUID storeId, LocalDate start, LocalDate end,
                         Function<List<AttachAttributionQuality>, T> reader) {
        // The nested warranty views otherwise spend seconds compiling thousands of JIT functions.
        // SET LOCAL is transaction-scoped, never a pool/global setting; rollback resets it on failure.
        String previousJit = jdbc.getJdbcTemplate().queryForObject("SELECT current_setting('jit')", String.class);
        jdbc.getJdbcTemplate().execute("SET LOCAL jit = off");
        List<AttachAttributionQuality> result = jdbc.query(QUERY,
                Map.of("storeId", storeId, "periodStart", start, "periodEnd", end),
                (row, index) -> new AttachAttributionQuality(row.getString("metric_code"),
                        row.getLong("pending_warranty_count"), row.getLong("unassigned_return_count"),
                        row.getLong("unassigned_metric_return_count"), row.getBoolean("pending_catalog_role")));
        T value = reader.apply(result);
        jdbc.queryForObject("SELECT set_config('jit', :previous, true)",
                Map.of("previous", previousJit), String.class);
        return value;
    }
}
