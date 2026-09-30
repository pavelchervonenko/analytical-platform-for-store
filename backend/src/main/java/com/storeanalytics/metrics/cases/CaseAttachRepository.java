package com.storeanalytics.metrics.cases;

import com.storeanalytics.common.exception.InvalidRequestException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CaseAttachRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public CaseAttachRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public CaseAttachViews.Queue queue(UUID storeId, String state, int offset, int limit) {
        if (!List.of("OPEN", "RESOLVED", "ALL").contains(state)
                || offset < 0 || limit < 1 || limit > 100) {
            throw new InvalidRequestException("Invalid case-review filter or page");
        }
        String open = "(decision_id IS NULL OR NOT COALESCE(decision_current, false) "
                + "OR decision_target_code = 'DEFER')";
        String filter = switch (state) {
            case "OPEN" -> " AND " + open;
            case "RESOLVED" -> " AND NOT " + open;
            default -> "";
        };
        Map<String, Object> params = Map.of("storeId", storeId, "offset", offset, "limit", limit);
        long total = jdbc.queryForObject("SELECT count(*) FROM case_attach_review_items "
                + "WHERE store_id = :storeId" + filter, params, Long.class);
        List<CaseAttachViews.Case> items = jdbc.query("""
                SELECT * FROM case_attach_review_items
                WHERE store_id = :storeId
                """ + filter + " ORDER BY business_date DESC, source_item_id DESC "
                + "LIMIT :limit OFFSET :offset", params, this::caseRow);
        Summary summary = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE decision_id IS NULL
                    OR NOT COALESCE(decision_current, false)
                    OR decision_target_code = 'DEFER') AS open_count,
                    count(*) FILTER (WHERE proposed_target = 'CONFLICT'
                    AND (decision_id IS NULL OR NOT COALESCE(decision_current, false)
                    OR decision_target_code = 'DEFER')) AS conflict_count,
                    COALESCE(sum(quantity) FILTER (WHERE decision_id IS NULL
                    OR NOT COALESCE(decision_current, false)
                    OR decision_target_code = 'DEFER'), 0) AS open_quantity
                FROM case_attach_review_items WHERE store_id = :storeId
                """, params, (row, ignored) -> new Summary(row.getLong("open_count"),
                row.getLong("conflict_count"), row.getBigDecimal("open_quantity")));
        return new CaseAttachViews.Queue(items, total, summary.openCount(),
                summary.conflictCount(), summary.openQuantity(), offset, limit);
    }

    public CaseAttachViews.Case find(UUID storeId, UUID sourceId) {
        return jdbc.query("""
                SELECT * FROM case_attach_review_items
                WHERE store_id = :storeId AND source_item_id = :sourceId
                """, Map.of("storeId", storeId, "sourceId", sourceId), this::caseRow)
                .stream().findFirst().orElse(null);
    }

    public List<CaseAttachViews.History> history(UUID storeId, UUID sourceId) {
        return jdbc.query("""
                SELECT id, revision, target_code, reason, actor_id, created_at
                FROM case_attach_decisions
                WHERE source_item_id = :sourceId AND store_id = :storeId
                ORDER BY revision DESC
                """, Map.of("sourceId", sourceId, "storeId", storeId), (row, ignored) -> new CaseAttachViews.History(
                row.getObject("id", UUID.class), row.getLong("revision"),
                row.getString("target_code"), row.getString("reason"),
                row.getObject("actor_id", UUID.class), row.getTimestamp("created_at").toInstant()));
    }

    public List<LocalDate> affectedDates(UUID storeId, UUID sourceId) {
        return jdbc.query("""
                SELECT DISTINCT business_date FROM (
                    SELECT document.business_date FROM sales_document_items item
                    JOIN sales_documents document ON document.id = item.sales_document_id
                    WHERE item.id = :sourceId AND document.store_id = :storeId
                      AND NOT item.is_deleted AND NOT document.is_deleted
                    UNION ALL
                    SELECT document.business_date FROM sales_document_items item
                    JOIN sales_documents document ON document.id = item.sales_document_id
                    JOIN sales_document_items original_item ON original_item.id = item.original_item_id
                    JOIN sales_documents original ON original.id = original_item.sales_document_id
                    JOIN analytics_categories item_category ON item_category.id = item.analytics_category_id
                    WHERE item.original_item_id = :sourceId AND document.store_id = :storeId
                      AND document.document_kind = 'RETURN' AND original.document_kind = 'SALE'
                      AND document.original_document_id = original.id
                      AND document.connection_id = original.connection_id
                      AND document.occurred_at >= original.occurred_at
                      AND document.store_id = original.store_id
                      AND item.product_id = original_item.product_id
                      AND item_category.code <> 'EXCLUDE'
                      AND NOT document.is_deleted AND NOT item.is_deleted
                      AND NOT original.is_deleted AND NOT original_item.is_deleted
                ) dates ORDER BY business_date
                """, Map.of("storeId", storeId, "sourceId", sourceId),
                (row, ignored) -> row.getObject("business_date", LocalDate.class));
    }

    public BigDecimal netQuantity(UUID storeId, UUID sourceId) {
        return jdbc.queryForObject("""
                SELECT source.quantity - COALESCE(sum(returned.quantity)
                    FILTER (WHERE return_document.id IS NOT NULL
                        AND returned_category.code <> 'EXCLUDE'), 0)
                FROM sales_document_items source
                JOIN sales_documents sale ON sale.id = source.sales_document_id
                LEFT JOIN sales_document_items returned ON returned.original_item_id = source.id
                  AND returned.product_id = source.product_id AND NOT returned.is_deleted
                LEFT JOIN analytics_categories returned_category
                  ON returned_category.id = returned.analytics_category_id
                LEFT JOIN sales_documents return_document
                  ON return_document.id = returned.sales_document_id
                  AND return_document.document_kind = 'RETURN'
                  AND NOT return_document.is_deleted
                  AND return_document.store_id = sale.store_id
                  AND return_document.connection_id = sale.connection_id
                  AND return_document.original_document_id = sale.id
                  AND return_document.occurred_at >= sale.occurred_at
                WHERE source.id = :sourceId AND sale.store_id = :storeId
                  AND sale.document_kind = 'SALE' AND NOT sale.is_deleted
                GROUP BY source.id
                """, Map.of("storeId", storeId, "sourceId", sourceId), BigDecimal.class);
    }

    public void lock(UUID storeId) {
        jdbc.getJdbcTemplate().queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 74772))",
                (row, ignored) -> null, storeId.toString());
    }

    public UUID save(CaseAttachViews.Case source, CaseAttachDecisionRequest request, UUID actorId,
                     UUID storeId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO case_attach_decisions (
                    id, source_item_id, store_id, target_code, source_fingerprint,
                    reason, revision, actor_id
                ) VALUES (:id, :sourceId, :storeId, :target, :fingerprint, :reason, :revision, :actor)
                """, Map.of("id", id, "sourceId", source.id(), "storeId", storeId,
                "target", request.targetCode(), "fingerprint", source.fingerprint(),
                "reason", request.reason().trim(), "revision", source.revision() + 1,
                "actor", actorId));
        return id;
    }

    public Map<String, BigDecimal> inferredUnits(UUID storeId, LocalDate start, LocalDate end) {
        return jdbc.query("""
                WITH eligible AS (
                    SELECT source_item_id, product_id, document_id, proposed_target
                    FROM case_attach_review_items
                    WHERE store_id = :storeId AND proposed_target IN ('IPHONE', 'SAMSUNG')
                      AND category_code IN ('OTHER_CASE', 'CASE_UNIVERSAL')
                      AND (decision_id IS NULL OR NOT COALESCE(decision_current, false)
                           OR decision_target_code = 'DEFER')
                ), facts AS (
                    SELECT eligible.proposed_target, document.business_date,
                           item.quantity AS net_quantity
                    FROM eligible
                    JOIN sales_document_items item ON item.id = eligible.source_item_id
                    JOIN sales_documents document ON document.id = item.sales_document_id
                    UNION ALL
                    SELECT eligible.proposed_target, document.business_date,
                           -item.quantity AS net_quantity
                    FROM eligible
                    JOIN sales_document_items item ON item.original_item_id = eligible.source_item_id
                       AND item.product_id = eligible.product_id AND NOT item.is_deleted
                    JOIN analytics_categories item_category ON item_category.id = item.analytics_category_id
                       AND item_category.code <> 'EXCLUDE'
                    JOIN sales_documents original ON original.id = eligible.document_id
                    JOIN sales_documents document ON document.id = item.sales_document_id
                       AND document.document_kind = 'RETURN' AND NOT document.is_deleted
                       AND document.store_id = :storeId
                       AND document.connection_id = original.connection_id
                       AND document.original_document_id = original.id
                       AND document.occurred_at >= original.occurred_at
                )
                SELECT proposed_target, COALESCE(sum(net_quantity), 0) AS quantity
                FROM facts WHERE business_date BETWEEN :start AND :end
                GROUP BY proposed_target
                """, Map.of("storeId", storeId, "start", start, "end", end),
                row -> {
                    Map<String, BigDecimal> result = new java.util.HashMap<>();
                    while (row.next()) {
                        result.put(row.getString("proposed_target"), row.getBigDecimal("quantity"));
                    }
                    return result;
                });
    }

    public long unresolvedReturnCount(UUID storeId, LocalDate start, LocalDate end) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM sales_documents document
                JOIN sales_document_items item ON item.sales_document_id = document.id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE document.store_id = :storeId
                  AND document.document_kind = 'RETURN'
                  AND document.business_date BETWEEN :start AND :end
                  AND category.code <> 'EXCLUDE'
                  AND (category.code IN ('OTHER_CASE', 'CASE_UNIVERSAL',
                      'GLASS_PHONE_UNRESOLVED', 'PROTECTIVE_FILM')
                      OR catalog_role_pending_issue(item.id) IS NOT NULL OR EXISTS (
                      SELECT 1 FROM case_attach_review_items candidate
                      WHERE candidate.source_item_id = item.original_item_id
                        AND candidate.store_id = document.store_id))
                  AND NOT document.is_deleted AND NOT item.is_deleted
                  AND NOT catalog_has_current_auto_role(item.id)
                  AND NOT EXISTS (
                    SELECT 1 FROM case_attach_review_items source
                    JOIN sales_documents original ON original.id = source.document_id
                    WHERE source.source_item_id = item.original_item_id
                      AND source.store_id = document.store_id
                      AND source.product_id = item.product_id
                      AND original.connection_id = document.connection_id
                      AND document.original_document_id = original.id
                      AND document.occurred_at >= original.occurred_at
                  )
                """, Map.of("storeId", storeId, "start", start, "end", end), Long.class);
    }

    public long periodCount(UUID storeId, LocalDate start, LocalDate end, String target) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM case_attach_review_items
                WHERE store_id = :storeId AND business_date BETWEEN :start AND :end
                  AND proposed_target = :target
                  AND category_code IN ('OTHER_CASE', 'CASE_UNIVERSAL')
                  AND (decision_id IS NULL OR NOT COALESCE(decision_current, false)
                       OR decision_target_code = 'DEFER')
                """, Map.of("storeId", storeId, "start", start, "end", end, "target", target),
                Long.class);
    }

    private CaseAttachViews.Case caseRow(ResultSet row, int ignored) throws SQLException {
        return new CaseAttachViews.Case(
                row.getObject("source_item_id", UUID.class), row.getObject("product_id", UUID.class),
                row.getString("product_code"), row.getString("product_name"),
                row.getObject("document_id", UUID.class), row.getString("document_number"),
                row.getObject("business_date", LocalDate.class), row.getBigDecimal("quantity"),
                row.getString("proposed_target"), row.getBoolean("has_iphone"),
                row.getBoolean("has_samsung"), row.getString("decision_target_code"),
                row.getBoolean("decision_current"), row.getLong("decision_revision"),
                row.getString("source_fingerprint"), row.getString("category_code"), allowedTargets(row));
    }

    private List<String> allowedTargets(ResultSet row) throws SQLException {
        var targets = row.getArray("allowed_targets");
        try {
            return List.of((String[]) targets.getArray());
        } finally {
            targets.free();
        }
    }

    private record Summary(long openCount, long conflictCount, BigDecimal openQuantity) { }
}
