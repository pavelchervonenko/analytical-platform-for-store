package com.storeanalytics.product.service;

import com.storeanalytics.product.model.CatalogCompatibilityEvidence;
import com.storeanalytics.product.model.CatalogCompatibilityEvidence.Context;
import com.storeanalytics.product.repository.CatalogCompatibilityRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Opt-in dated role capture for newly inserted sync items; catalog projections consume immutable snapshots. */
@Service
public class CatalogSaleRoleSnapshotWriter {
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final CatalogCompatibilityRepository compatibility;
    private final CatalogCompatibilityProjectionService projection;
    private final Clock clock;
    private final boolean enabled;
    private final Instant captureFrom;

    public CatalogSaleRoleSnapshotWriter(NamedParameterJdbcTemplate jdbc, EntityManager entityManager,
            CatalogCompatibilityRepository compatibility, CatalogCompatibilityProjectionService projection,
            Clock clock, @Value("${app.catalog-compatibility.snapshots-enabled:false}") boolean enabled,
            @Value("${app.catalog-compatibility.snapshots-from:}") String captureFrom) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.compatibility = compatibility;
        this.projection = projection;
        this.clock = clock;
        this.enabled = enabled;
        if (enabled && (captureFrom == null || captureFrom.isBlank())) {
            throw new IllegalArgumentException("Role capture requires an explicit prospective start");
        }
        this.captureFrom = enabled ? Instant.parse(captureFrom) : null;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void captureNewItem(UUID itemId) {
        if (!enabled) {
            return;
        }
        // JDBC must see new/updated JPA facts, including the merged product observation, in this transaction.
        entityManager.flush();
        var parameters = Map.of("id", itemId);
        var rows = jdbc.query("""
                SELECT i.product_id, i.product_name_snapshot, d.document_kind, d.occurred_at, c.code
                FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
                JOIN products p ON p.id = i.product_id
                JOIN analytics_categories c ON c.id = i.analytics_category_id
                WHERE i.id = :id AND NOT i.is_deleted AND NOT d.is_deleted
                  AND p.source_system = 'LIVESKLAD'
                  AND (d.document_kind = 'RETURN' OR (p.source_kind = 'PRODUCT' AND p.is_active))
                  AND NOT EXISTS (SELECT 1 FROM catalog_sale_role_snapshots s WHERE s.item_id = i.id)
                FOR UPDATE OF i
                """, parameters, (row, index) -> new Fact(row.getObject("product_id", UUID.class),
                row.getString("product_name_snapshot"), row.getString("document_kind"),
                row.getTimestamp("occurred_at").toInstant(), row.getString("code")));
        if (rows.isEmpty()) {
            return;
        }
        var fact = rows.getFirst();
        if (fact.occurredAt().isBefore(captureFrom) || fact.occurredAt().isAfter(clock.instant())
                || CatalogCategoryRegistry.standard().find(fact.category()).isEmpty()) {
            return; // No retroactive capture or fabricated categories for unsupported source facts.
        }
        if ("RETURN".equals(fact.kind())) {
            inheritReturn(itemId);
        } else {
            captureSale(itemId, fact);
        }
    }

    private void captureSale(UUID itemId, Fact fact) {
        var observation = compatibility.observe(fact.product(), true);
        String fingerprint = CatalogCompatibilityEvidence.observationFingerprint(
                observation.subject(), fact.name(), observation.groupPath());
        var result = projection.project(fact.product(), fact.category(),
                new Context(observation.subject(), fingerprint, fact.occurredAt()));
        var decision = result.decision();
        var parameters = new MapSqlParameterSource().addValue("id", itemId)
                .addValue("category", fact.category()).addValue("policy", CatalogAccessoryAttachPolicy.VERSION)
                .addValue("registry", CatalogCategoryRegistry.standard().sha256())
                .addValue("outcome", decision.outcome().name())
                .addValue("role", decision.role() == null ? null : decision.role().name())
                .addValue("reason", decision.reason().name()).addValue("confirmation", result.confirmationId())
                .addValue("fingerprint", fingerprint).addValue("group", observation.groupPath())
                .addValue("connectionKey", observation.subject().connectionKey())
                .addValue("external", observation.subject().id());
        jdbc.update("""
                INSERT INTO catalog_sale_role_snapshots
                    (item_id,fact_identity,monetary_category,policy_version,registry_sha256,outcome,role,
                     reason,origin,confirmation_id,observation_fingerprint,observed_group_path,
                     observed_connection_key,observed_external_id)
                VALUES (:id,catalog_sale_role_fact(:id),:category,:policy,:registry,:outcome,:role,
                     :reason,'SALE_PROJECTION',:confirmation,:fingerprint,:group,:connectionKey,:external)
                ON CONFLICT (item_id) DO NOTHING
                """, parameters);
    }

    private void inheritReturn(UUID itemId) {
        var parameters = new MapSqlParameterSource("id", itemId)
                .addValue("policy", CatalogAccessoryAttachPolicy.VERSION)
                .addValue("registry", CatalogCategoryRegistry.standard().sha256());
        int inherited = jdbc.update("""
                INSERT INTO catalog_sale_role_snapshots
                    (item_id,fact_identity,monetary_category,policy_version,registry_sha256,outcome,role,reason,
                     origin,original_snapshot_item_id,confirmation_id,observation_fingerprint,observed_group_path,
                     observed_connection_key,observed_external_id)
                SELECT i.id,catalog_sale_role_fact(i.id),s.monetary_category,s.policy_version,s.registry_sha256,
                     s.outcome,s.role,s.reason,'ORIGINAL_SALE',s.item_id,s.confirmation_id,
                     s.observation_fingerprint,s.observed_group_path,s.observed_connection_key,s.observed_external_id
                FROM sales_document_items i JOIN sales_documents d ON d.id = i.sales_document_id
                JOIN sales_document_items oi ON oi.id = i.original_item_id
                JOIN sales_documents od ON od.id = oi.sales_document_id
                JOIN analytics_categories c ON c.id = i.analytics_category_id
                JOIN catalog_sale_role_snapshot_states s ON s.item_id = oi.id
                WHERE i.id = :id AND s.state = 'CURRENT' AND s.origin = 'SALE_PROJECTION'
                  AND c.code = s.monetary_category AND i.product_id = oi.product_id
                  AND d.original_document_id = od.id AND d.store_id = od.store_id
                  AND d.connection_id = od.connection_id AND d.occurred_at >= od.occurred_at
                ON CONFLICT (item_id) DO NOTHING
                """, parameters);
        if (inherited == 0) {
            jdbc.update("""
                    INSERT INTO catalog_sale_role_snapshots
                        (item_id,fact_identity,monetary_category,policy_version,registry_sha256,
                         outcome,reason,origin)
                    SELECT i.id,catalog_sale_role_fact(i.id),c.code,:policy,:registry,
                        'DEFER_TO_EXISTING','ORIGINAL_SNAPSHOT_UNAVAILABLE','LEGACY_RETURN'
                    FROM sales_document_items i JOIN analytics_categories c ON c.id = i.analytics_category_id
                    WHERE i.id = :id ON CONFLICT (item_id) DO NOTHING
                    """, parameters);
        }
    }

    private record Fact(UUID product, String name, String kind, Instant occurredAt, String category) { }
}
