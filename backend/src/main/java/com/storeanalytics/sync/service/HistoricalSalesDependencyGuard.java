package com.storeanalytics.sync.service;

import com.storeanalytics.sync.exception.HistoricalSalesDependencyException;
import com.storeanalytics.sync.exception.HistoricalSalesDependencyException.LinkedReturn;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact transactional postcondition: a SALE-only refresh cannot leave linked RETURN facts stale. */
@Service
public class HistoricalSalesDependencyGuard {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public HistoricalSalesDependencyGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public void lockConnection(UUID connection) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SALE/RETURN publication lock requires an active transaction");
        }
        // Unconditional across API/worker roles: otherwise API recovery could race a worker-only sweep.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                Object.class, "historical-sale-return:" + connection);
    }

    public Snapshot capture(UUID connection, SalesSyncPeriod period, List<StoreSalesBatch> batches) {
        Map<UUID, String> before = new HashMap<>();
        for (var batch : batches) {
            var ids = batch.sales().stream().map(sale -> sale.summary().externalId()).toList();
            String incoming = ids.isEmpty() ? "" : " OR d.external_id IN (:ids)";
            var parameters = new HashMap<String, Object>();
            parameters.put("connection", connection);
            parameters.put("store", batch.store().getId());
            parameters.put("start", Timestamp.from(period.start()));
            parameters.put("end", Timestamp.from(period.end()));
            parameters.put("ids", ids);
            named.query("""
                    SELECT d.id FROM sales_documents d WHERE d.connection_id = :connection
                    AND ((d.store_id = :store AND d.document_kind = 'SALE' AND d.source_document_type = 'sale'
                          AND NOT d.is_deleted AND d.occurred_at >= :start AND d.occurred_at < :end)
                    """ + incoming + ")", parameters, row -> {
                        UUID id = row.getObject("id", UUID.class);
                        before.put(id, signature(id));
                    });
        }
        return new Snapshot(period.start(), period.end(), Map.copyOf(before));
    }

    public void verify(Snapshot before) {
        Instant start = before.start();
        Instant end = before.end();
        boolean blocked = false;
        boolean repairable = true;
        List<LinkedReturn> dependencies = new ArrayList<>();
        for (var entry : before.signatures().entrySet()) {
            if (entry.getValue().equals(signature(entry.getKey()))) {
                continue;
            }
            var returns = jdbc.queryForList("""
                    SELECT id, occurred_at, source_system, source_document_type FROM sales_documents
                    WHERE original_document_id = ? AND document_kind = 'RETURN'
                    """, entry.getKey());
            if (returns.isEmpty()) {
                continue;
            }
            blocked = true;
            for (var returned : returns) {
                dependencies.add(new LinkedReturn((UUID) returned.get("id"), entry.getKey()));
                Instant at = ((Timestamp) returned.get("occurred_at")).toInstant();
                start = at.isBefore(start) ? at : start;
                Instant after = at.plusMillis(1); // Remains strictly exclusive at PostgreSQL microsecond precision.
                end = after.isAfter(end) ? after : end;
                repairable &= "LIVESKLAD".equals(returned.get("source_system"))
                        && "saleReturn".equalsIgnoreCase((String) returned.get("source_document_type"));
            }
        }
        if (blocked) {
            throw new HistoricalSalesDependencyException(start, end, repairable, dependencies);
        }
    }

    /** Call under the connection publication lock; each identity must still exist and be freshly applied. */
    public boolean repairSatisfied(UUID connection, UUID backfill, Instant blockedAt, List<LinkedReturn> dependencies) {
        if (dependencies.isEmpty()) {
            return false;
        }
        for (var dependency : dependencies) {
            Boolean accepted = jdbc.query("""
                    SELECT r.original_document_id = p.id AND r.connection_id = p.connection_id
                        AND r.store_id = p.store_id AND r.source_system = 'LIVESKLAD'
                        AND lower(r.source_document_type) = 'salereturn' AND r.document_kind = 'RETURN'
                        AND p.source_system = 'LIVESKLAD' AND p.source_document_type = 'sale'
                        AND p.document_kind = 'SALE'
                        AND pr.sync_job_id = ? AND pr.sync_scope = 'SALES'
                        AND pr.connection_id = p.connection_id AND pr.source_system = 'LIVESKLAD'
                        AND pr.status IN ('SUCCESS', 'PARTIAL_SUCCESS') AND pr.started_at > ?
                        AND pv.connection_id = p.connection_id AND pv.store_id = p.store_id
                        AND pv.source_system = p.source_system AND pv.entity_type = 'SALE_DOCUMENT'
                        AND pv.external_id = p.external_id AND pv.normalization_status = 'NORMALIZED'
                        AND pv.last_sync_run_id = p.last_sync_run_id
                        AND cr.connection_id = r.connection_id AND cr.source_system = 'LIVESKLAD'
                        AND cr.sync_scope = 'RETURNS' AND cr.status IN ('SUCCESS', 'PARTIAL_SUCCESS')
                        AND cr.started_at > ? AND cr.started_at >= pr.finished_at
                        AND rv.connection_id = r.connection_id AND rv.store_id = r.store_id
                        AND rv.source_system = r.source_system AND rv.entity_type = 'RETURN_DOCUMENT'
                        AND rv.external_id = r.external_id AND rv.normalization_status = 'NORMALIZED'
                        AND rv.last_sync_run_id = r.last_sync_run_id
                        -- Compare publication clocks only within the DB clock domain. Idempotent accepted
                        -- rereads update last_sync_run_id, while rejected raw observations do not.
                        AND r.updated_at > greatest(p.updated_at,
                            (SELECT max(original.updated_at) FROM sales_document_items original
                             WHERE original.sales_document_id = p.id))
                        AND CASE WHEN r.is_deleted THEN
                            jsonb_array_length(rv.payload -> 'cashTransactions') > 0
                            AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(rv.payload -> 'cashTransactions') cash
                                WHERE lower(cash ->> 'type') IS DISTINCT FROM 'delete'
                                   OR cash -> 'document' ->> 'id' IS DISTINCT FROM r.external_id)
                        ELSE NOT p.is_deleted AND r.employee_id IS NOT DISTINCT FROM p.employee_id
                            AND rv.payload -> 'detail' ->> 'id' = r.external_id
                            AND lower(rv.payload -> 'detail' ->> 'type') = 'salereturn'
                            AND EXISTS (SELECT 1 FROM sales_document_items i
                                WHERE i.sales_document_id = r.id AND NOT i.is_deleted)
                            AND NOT EXISTS (SELECT 1 FROM sales_document_items i
                                LEFT JOIN sales_document_items original ON original.id = i.original_item_id
                                WHERE i.sales_document_id = r.id AND NOT i.is_deleted
                                  AND (original.id IS NULL OR original.sales_document_id <> p.id OR original.is_deleted
                                    OR i.product_id <> original.product_id
                                    OR ROW(i.product_name_snapshot, i.source_group_name_snapshot,
                                        i.analytics_category_id, i.category_assignment_id,
                                        i.classification_version, i.condition_type_snapshot)
                                       IS DISTINCT FROM ROW(original.product_name_snapshot,
                                        original.source_group_name_snapshot, original.analytics_category_id,
                                        original.category_assignment_id, original.classification_version,
                                        original.condition_type_snapshot))) END AS accepted
                    FROM sales_documents r JOIN sales_documents p ON p.id = ? AND p.connection_id = ?
                    JOIN sync_runs pr ON pr.id = p.last_sync_run_id
                    JOIN raw_record_versions pv ON pv.id = p.raw_record_version_id
                    JOIN sync_runs cr ON cr.id = r.last_sync_run_id
                    JOIN raw_record_versions rv ON rv.id = r.raw_record_version_id
                    WHERE r.id = ?
                    """, (row, index) -> row.getBoolean("accepted"), backfill, Timestamp.from(blockedAt),
                    Timestamp.from(blockedAt), dependency.parentId(), connection, dependency.returnId())
                    .stream().findFirst().orElse(false);
            if (!Boolean.TRUE.equals(accepted)) {
                return false;
            }
        }
        return true;
    }

    private String signature(UUID id) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'document', jsonb_build_array(d.connection_id, d.external_id, d.store_id, d.employee_id,
                        d.original_document_id, d.document_kind, d.source_document_type, d.source_status,
                        d.occurred_at, d.business_date, d.net_amount, d.cost_amount, d.is_deleted),
                    'items', (SELECT jsonb_agg(jsonb_build_array(i.external_id, i.original_item_id,
                        i.product_name_snapshot,
                        i.product_id, i.analytics_category_id, i.category_assignment_id, i.classification_version,
                        i.condition_type_snapshot, i.source_group_name_snapshot, i.quantity, i.unit_price,
                        i.gross_amount, i.discount_amount, i.net_amount, i.cost_amount, i.cost_quality,
                        i.is_work, i.is_deleted) ORDER BY i.external_id)
                        FROM sales_document_items i WHERE i.sales_document_id = d.id))::text
                FROM sales_documents d WHERE d.id = ?
                """, String.class, id);
    }

    public record Snapshot(Instant start, Instant end, Map<UUID, String> signatures) { }
}
