package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class WarrantyFingerprintContextMigrationIntegrationTest {
    private static final List<String> VIEWS = List.of("warranty_attach_sources", "warranty_attach_cases",
            "warranty_attach_effective_allocations", "attach_rate_item_facts_v4");
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private UUID connectionId;
    private UUID store;
    private UUID otherStore;
    private UUID employee;
    private UUID actor;
    private UUID sync;

    @Test
    void upgradePreservesEveryProjectionFingerprintAndInvalidationPredicate() throws Exception {
        flyway("79").migrate();
        try (Connection connection = connection()) {
            jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            fixture();
            UUID targetDoc = document("SALE", null);
            UUID fresh = item(targetDoc, "Device", "SAMSUNG_NEW", "NEW", null);
            UUID used = item(targetDoc, "Device", "IPHONE_USED", "USED", null);
            item(targetDoc, "Unknown", "IPHONE_NEW_ASIS", "UNKNOWN", null);
            UUID deleted = item(targetDoc, "Deleted", "SAMSUNG_NEW", "NEW", null);
            jdbc.update("UPDATE sales_document_items SET is_deleted = true WHERE id = ?", deleted);
            UUID manual = item(document("SALE", null), "Warranty", "WARRANTY_GENERIC", "NOT_APPLICABLE", null);
            jdbc.update("UPDATE sales_document_items SET quantity = 2 WHERE id = ?", manual);
            allocate(connection, manual, List.of(fresh, used));
            UUID autoDoc = document("SALE", null);
            item(autoDoc, "Device", "IPHONE_USED", "USED", null);
            UUID automatic = item(autoDoc, "Warranty", "WARRANTY_GENERIC", "NOT_APPLICABLE", null);
            UUID care = item(autoDoc, "Future Store Privilege Care", "WARRANTY_GENERIC", "NOT_APPLICABLE", null);
            item(document("RETURN", autoDoc), "Warranty", "WARRANTY_GENERIC", "NOT_APPLICABLE", automatic);
            UUID returned = item(document("RETURN", null), "Warranty", "WARRANTY_GENERIC", "NOT_APPLICABLE", manual);
            allocate(connection, returned, List.of(fresh));
            item(document("RETURN", null), "Orphan", "WARRANTY_GENERIC", "NOT_APPLICABLE", null);
            UUID deferred = item(document("SALE", null), "Deferred", "WARRANTY_GENERIC", "NOT_APPLICABLE", null);
            decide(deferred, "DEFER");
            UUID excluded = item(document("SALE", null), "Excluded", "WARRANTY_GENERIC", "NOT_APPLICABLE", null);
            decide(excluded, "EXCLUDE");
            assertThat(jdbc.queryForList("SELECT state FROM warranty_attach_cases", String.class))
                    .contains("RESOLVED_MANUAL", "RESOLVED_AUTO", "CONFLICT", "DEFERRED", "EXCLUDED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM warranty_attach_sources WHERE id = ?",
                    Long.class, care)).isZero();
            String legacyView = "CREATE OR REPLACE VIEW warranty_attach_sources AS "
                    + jdbc.queryForObject("SELECT pg_get_viewdef('warranty_attach_sources'::regclass, true)",
                    String.class);
            Map<String, List<String>> before = projections();
            flyway(null).migrate();
            assertThat(projections()).isEqualTo(before);
            String optimizedView = optimizedView();
            assertThat(jdbc.queryForObject("SELECT pg_get_viewdef('warranty_attach_sources'::regclass, true)",
                    String.class)).contains("LATERAL");
            compareMutation(connection, legacyView, optimizedView,
                    "UPDATE sales_document_items SET quantity = quantity + 1 WHERE id = ?", fresh);
            compareMutation(connection, legacyView, optimizedView,
                    "UPDATE sales_document_items SET is_deleted = true WHERE id = ?", fresh);
            compareMutation(connection, legacyView, optimizedView,
                    "UPDATE sales_document_items SET condition_type_snapshot = 'UNKNOWN' WHERE id = ?", fresh);
            compareMutation(connection, legacyView, optimizedView,
                    "UPDATE sales_documents SET is_deleted = true WHERE id = ?", targetDoc);
            compareMutation(connection, legacyView, optimizedView,
                    "UPDATE sales_documents SET store_id = ? WHERE id = ?", otherStore, targetDoc);
            compareMutation(connection, legacyView, optimizedView,
                    "UPDATE sales_document_items SET quantity = quantity + 1 WHERE id = ?", care);
            compareOriginalRevision(connection, legacyView, optimizedView, manual);
            assertThatThrownBy(() -> jdbc.update("UPDATE warranty_attach_decisions SET reason = 'Changed'"))
                    .hasStackTraceContaining("Warranty attribution history is immutable");
            assertThatThrownBy(() -> decide(item(document("SALE", null), "Incomplete",
                    "WARRANTY_GENERIC", "NOT_APPLICABLE", null), "ALLOCATE"))
                    .hasStackTraceContaining("Incomplete warranty allocation");
        }
    }

    private void compareMutation(Connection connection, String legacy, String optimized,
                                 String mutation, Object... args) throws SQLException {
        connection.setAutoCommit(false);
        try {
            jdbc.execute(legacy);
            jdbc.update(mutation, args);
            Map<String, List<String>> before = projections();
            jdbc.execute(optimized);
            assertThat(projections()).isEqualTo(before);
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private void compareOriginalRevision(Connection connection, String legacy, String optimized,
                                         UUID source) throws SQLException {
        connection.setAutoCommit(false);
        try {
            jdbc.execute(legacy);
            decide(source, "EXCLUDE");
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            Map<String, List<String>> before = projections();
            jdbc.execute(optimized);
            assertThat(projections()).isEqualTo(before);
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private Map<String, List<String>> projections() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String view : VIEWS) {
            result.put(view, jdbc.queryForList("SELECT to_jsonb(row)::text FROM " + view
                    + " row ORDER BY to_jsonb(row)::text", String.class));
        }
        return result;
    }

    private void allocate(Connection connection, UUID source, List<UUID> targets) throws SQLException {
        connection.setAutoCommit(false);
        try {
            UUID decision = decide(source, "ALLOCATE");
            for (UUID target : targets) {
                jdbc.update("""
                        INSERT INTO warranty_attach_allocations (decision_id, device_item_id, quantity,
                            target_fingerprint, device_document_id, device_type, business_date, employee_id)
                        SELECT ?, target.id, 1, md5(concat_ws(':', target.fingerprint, context.fingerprint)),
                            target.document_id, target.device_type, target.business_date, target.employee_id
                        FROM warranty_attach_items target JOIN warranty_attach_document_context context
                            ON context.document_id = target.document_id WHERE target.id = ?
                        """, decision, target);
            }
            connection.commit();
        } finally {
            connection.rollback();
            connection.setAutoCommit(true);
        }
    }

    private UUID decide(UUID source, String action) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO warranty_attach_decisions (id, source_item_id, revision, action,
                    source_fingerprint, actor_id, reason)
                SELECT ?, id, revision + 1, ?, source_fingerprint, ?, 'Synthetic verification'
                FROM warranty_attach_sources WHERE id = ?
                """, id, action, actor, source);
        return id;
    }

    private void fixture() {
        connectionId = jdbc.queryForObject("SELECT id FROM integration_connections LIMIT 1", UUID.class);
        store = store();
        otherStore = store();
        sync = UUID.randomUUID();
        actor = UUID.randomUUID();
        employee = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sync_runs (id, connection_id, source_system, trigger_type, sync_scope, status,
                    started_at, finished_at) VALUES (?, ?, 'LIVESKLAD', 'MANUAL', 'SALES', 'SUCCESS', now(), now())
                """, sync, connectionId);
        jdbc.update("""
                INSERT INTO app_users (id, email, password_hash, display_name, role, password_change_required)
                VALUES (?, ?, 'not-a-login-hash', 'Synthetic manager', 'ADMIN', false)
                """, actor, actor + "@example.com");
        jdbc.update("INSERT INTO employees (id, connection_id, external_id, full_name) VALUES (?, ?, ?, ?)",
                employee, connectionId, employee.toString(), "Synthetic employee");
    }

    private UUID store() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO stores (id, connection_id, source_system, external_id, name)
                VALUES (?, ?, 'LIVESKLAD', ?, 'Synthetic store')
                """, id, connectionId, id.toString());
        return id;
    }

    private UUID document(String kind, UUID original) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sales_documents (id, connection_id, external_id, store_id, employee_id,
                    original_document_id, document_kind, source_document_type, occurred_at, business_date,
                    net_amount, cost_amount, last_sync_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'sale', '2026-08-10T12:00:00Z', '2026-08-10', 1, 1, ?)
                """, id, connectionId, id.toString(), store, employee, original, kind, sync);
        return id;
    }

    private UUID item(UUID document, String name, String category, String condition, UUID original) {
        UUID id = UUID.randomUUID();
        UUID product = original == null ? UUID.randomUUID()
                : jdbc.queryForObject("SELECT product_id FROM sales_document_items WHERE id = ?", UUID.class, original);
        if (original == null) {
            jdbc.update("INSERT INTO products (id, connection_id, external_id, name) VALUES (?, ?, ?, ?)",
                    product, connectionId, product.toString(), name);
        }
        jdbc.update("""
                INSERT INTO sales_document_items (id, sales_document_id, external_id, product_id,
                    original_item_id, product_name_snapshot, analytics_category_id, condition_type_snapshot,
                    quantity, unit_price, gross_amount, net_amount, cost_amount, cost_quality)
                SELECT ?, ?, ?, ?, ?, ?, id, ?, 1, 1, 1, 1, 1, 'KNOWN'
                FROM analytics_categories WHERE code = ?
                """, id, document, id.toString(), product, original, name, condition, category);
        return id;
    }

    private String optimizedView() throws IOException {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V80__bound_warranty_fingerprint_context_to_document.sql")) {
            return new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
