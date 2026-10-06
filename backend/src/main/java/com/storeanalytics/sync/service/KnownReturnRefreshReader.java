package com.storeanalytics.sync.service;

import com.storeanalytics.integration.livesklad.exception.LiveSkladReturnChangedException;
import com.storeanalytics.store.model.Store;
import com.storeanalytics.sync.model.SyncTriggerType;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded known-fact refresh for a fenced durable BACKFILL RETURNS phase, not source discovery. */
@Service
public class KnownReturnRefreshReader {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;
    private final HistoricalSalesDependencyGuard dependencies;
    private final EntityManager entityManager;

    public KnownReturnRefreshReader(JdbcTemplate jdbc, Clock clock, HistoricalSalesDependencyGuard dependencies,
                                    EntityManager entityManager) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
        this.dependencies = dependencies;
        this.entityManager = entityManager;
    }

    public List<KnownReturn> read(UUID connection, List<Store> stores, ReturnSyncPeriod period,
                                 SyncExecutionContext context, int maximum) {
        if (!enabled(context)) {
            return List.of();
        }
        validateJob(connection, period, context, false);
        Map<String, Object> parameters = parameters(connection, stores, period);
        parameters.put("limit", maximum + 1);
        return named.query("""
                SELECT d.id, d.external_id, d.store_id, d.source_updated_at, d.version
                FROM sales_documents d JOIN stores s ON s.id = d.store_id
                WHERE d.connection_id = :connection AND s.connection_id = :connection
                  AND d.store_id IN (:stores) AND s.is_active AND NOT d.is_deleted
                  AND d.source_system = 'LIVESKLAD' AND d.document_kind = 'RETURN'
                  AND lower(d.source_document_type) = 'salereturn'
                  AND d.occurred_at >= :start AND d.occurred_at < :end
                ORDER BY d.external_id LIMIT :limit
                """, parameters, (row, index) -> new KnownReturn(row.getObject("id", UUID.class),
                        row.getString("external_id"), row.getObject("store_id", UUID.class),
                        nullable(row.getTimestamp("source_updated_at")), row.getLong("version")));
    }

    public boolean enabled(SyncExecutionContext context) {
        return context.triggerType() == SyncTriggerType.INITIAL;
    }

    /** Recheck lease and anchors under job/connection locks before normalizer publication. */
    @Transactional
    public <T> T publish(UUID connection, List<Store> stores, ReturnSyncPeriod period,
                         SyncExecutionContext context, List<KnownReturn> known,
                         List<StoreReturnBatch> batches, Supplier<T> publication) {
        if (!enabled(context)) {
            throw new IllegalArgumentException("Known RETURN publication requires BACKFILL context");
        }
        validateJob(connection, period, context, true);
        dependencies.lockConnection(connection);
        if (!known.isEmpty()) {
            Map<String, Object> parameters = parameters(connection, stores, period);
            parameters.put("ids", known.stream().map(KnownReturn::id).toList());
            List<KnownReturn> current = named.query("""
                    SELECT d.id, d.external_id, d.store_id, d.source_updated_at, d.version
                    FROM sales_documents d JOIN stores s ON s.id = d.store_id
                    WHERE d.id IN (:ids) AND d.connection_id = :connection AND s.connection_id = :connection
                      AND d.store_id IN (:stores) AND s.is_active AND NOT d.is_deleted
                      AND d.source_system = 'LIVESKLAD' AND d.document_kind = 'RETURN'
                      AND lower(d.source_document_type) = 'salereturn'
                      AND d.occurred_at >= :start AND d.occurred_at < :end
                    """, parameters, (row, index) -> new KnownReturn(row.getObject("id", UUID.class),
                            row.getString("external_id"), row.getObject("store_id", UUID.class),
                            nullable(row.getTimestamp("source_updated_at")), row.getLong("version")));
            if (current.size() != known.size()) {
                throw new IllegalStateException("Known RETURN scope changed before publication");
            }
            Map<UUID, KnownReturn> expected = new HashMap<>();
            known.forEach(item -> expected.put(item.id(), item));
            for (KnownReturn item : current) {
                KnownReturn prior = expected.get(item.id());
                if (prior == null || !prior.externalId().equals(item.externalId())
                        || !prior.storeId().equals(item.storeId())) {
                    throw new IllegalStateException("Known RETURN identity changed before publication");
                }
                if (prior.version() != item.version()) {
                    throw new LiveSkladReturnChangedException();
                }
                var source = batches.stream().filter(batch -> batch.store().getId().equals(item.storeId()))
                        .flatMap(batch -> batch.returns().stream())
                        .filter(candidate -> candidate.externalId().equals(item.externalId())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Known RETURN source is missing"));
                if (!source.deleted()) {
                    validate(item, source, period);
                }
            }
        }
        T result = publication.get();
        entityManager.flush();
        validateJob(connection, period, context, false);
        return result;
    }

    public void validate(KnownReturn known, LiveSkladReturnSource source, ReturnSyncPeriod period) {
        var detail = source.detail();
        if (detail == null || !known.externalId().equals(detail.externalId())
                || !"saleReturn".equalsIgnoreCase(detail.sourceType()) || detail.occurredAt() == null
                || detail.occurredAt().isBefore(period.start()) || !detail.occurredAt().isBefore(period.end())) {
            throw new IllegalStateException("Known RETURN detail changed identity or occurrence scope");
        }
        Instant candidate = detail.sourceUpdatedAt() == null ? detail.occurredAt() : detail.sourceUpdatedAt();
        for (var cash : source.cashTransactions()) {
            Instant version = cash.sourceUpdatedAt() == null ? cash.occurredAt() : cash.sourceUpdatedAt();
            if (version.isAfter(candidate)) {
                candidate = version;
            }
        }
        if (known.sourceUpdatedAt() != null && candidate.isBefore(known.sourceUpdatedAt())) {
            throw new IllegalStateException("Known RETURN source version is stale");
        }
    }

    private void validateJob(UUID connection, ReturnSyncPeriod period, SyncExecutionContext context, boolean lock) {
        if (context.syncJobId() == null || context.jobAttempt() == null) {
            throw new IllegalArgumentException("Known RETURN refresh requires an exact durable job attempt");
        }
        Boolean valid = jdbc.query("""
                SELECT connection_id = ? AND job_type = 'BACKFILL' AND status = 'RUNNING' AND phase = 'RETURNS'
                    AND NOT cancel_requested AND lease_until > ? AND attempt_count = ?
                    AND cursor_start = ? AND current_window_end = ?
                    AND requested_by IS NOT DISTINCT FROM CAST(? AS uuid) AS valid
                FROM sync_jobs WHERE id = ?
                """ + (lock ? " FOR UPDATE" : ""), (row, index) -> row.getBoolean("valid"), connection,
                Timestamp.from(clock.instant()), context.jobAttempt(), Timestamp.from(period.start()),
                Timestamp.from(period.end()), context.requestedBy() == null ? null : context.requestedBy().getId(),
                context.syncJobId()).stream().findFirst().orElse(false);
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalStateException("Known RETURN refresh lost its durable BACKFILL RETURNS scope");
        }
    }

    private Map<String, Object> parameters(UUID connection, List<Store> stores, ReturnSyncPeriod period) {
        return new HashMap<>(Map.of("connection", connection, "stores", stores.stream().map(Store::getId).toList(),
                "start", Timestamp.from(period.start()), "end", Timestamp.from(period.end())));
    }

    private static Instant nullable(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record KnownReturn(UUID id, String externalId, UUID storeId, Instant sourceUpdatedAt, long version) { }
}
