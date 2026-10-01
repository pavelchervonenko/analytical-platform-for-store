package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ChargingStationsAndTrackersMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void preservesHistoricalRowsDuringProspectiveSchemaPreparation() throws SQLException {
        flyway("76").migrate();
        addFixtures();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var before = HistoricalCatalogRows.snapshot(connection);
            flyway("77").migrate();
            assertThat(HistoricalCatalogRows.snapshot(connection)).isEqualTo(before);
            flyway("90").migrate();
            assertThat(HistoricalCatalogRows.snapshot(connection)).isEqualTo(before);
        }
    }

    private void addFixtures() throws SQLException {
        execute("""
                INSERT INTO stores (id, connection_id, source_system, external_id, name)
                SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                       fixture.external_id, fixture.name
                FROM integration_connections connection
                CROSS JOIN (VALUES
                    ('00000000-0000-4000-8000-000000000771', 'charger-store-a', 'A'),
                    ('00000000-0000-4000-8000-000000000772', 'charger-store-b', 'B')
                ) fixture(id, external_id, name)
                WHERE connection.connection_key = 'livesklad-default'
                """);
        execute("""
                INSERT INTO sync_runs (
                    id, connection_id, store_id, source_system,
                    trigger_type, sync_scope, status, started_at, finished_at
                )
                SELECT '00000000-0000-4000-8000-000000000773',
                       id, '00000000-0000-4000-8000-000000000771',
                       'LIVESKLAD', 'MANUAL', 'SALES', 'SUCCESS',
                       '2026-09-01T10:00:00Z', '2026-09-01T10:01:00Z'
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """);
        execute("""
                INSERT INTO products (
                    connection_id, source_system, external_id, code, name, source_kind
                )
                SELECT connection.id, 'LIVESKLAD', fixture.code,
                       fixture.code, fixture.name, 'PRODUCT'
                FROM integration_connections connection
                CROSS JOIN (VALUES
                    ('3481', 'CЗУ Ugreen X512 Type-C 20W белый'),
                    ('3480', 'CЗУ Ugreen X512 Type-C 20W черный'),
                    ('4013', 'CЗУ Ugreen X513 Type-C 30W  белый'),
                    ('3390', 'Taggy Keephone белый'),
                    ('3391', 'Taggy Keephone черный'),
                    ('6108', 'USB-C - Lightning No Box'),
                    ('4350', 'Беспроводное зар. устройство VLP Lite Power Snap Qi2 Apple Watch'),
                    ('71', 'Станция 3 в 1 (Стоячая)'),
                    ('9999', 'Станция 3 в 1 (Стоячая)')
                ) fixture(code, name)
                WHERE connection.connection_key = 'livesklad-default'
                """);
        execute("""
                INSERT INTO product_category_assignments (
                    product_id, analytics_category_id, condition_type,
                    assignment_source, rule_version, valid_from
                )
                SELECT product.id, category.id, 'NEW', 'AUTO',
                       'fixture-v1', '2026-09-01T00:00:00Z'
                FROM products product
                JOIN analytics_categories category ON category.code =
                    CASE WHEN product.code IN ('3390', '3391')
                             THEN 'CHARGER_CABLE'
                         WHEN product.code = '4350'
                             THEN 'PODS_WATCH_OTHER_DEVICE'
                         ELSE 'OTHER_ACCESSORY_PRODUCT' END
                WHERE product.code <> '71'
                """);
        execute("""
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id,
                    document_kind, source_document_type, occurred_at,
                    business_date, net_amount, cost_amount, last_sync_run_id
                )
                SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                       fixture.external_id, fixture.store_id::uuid,
                       fixture.kind, fixture.kind, fixture.occurred_at::timestamptz,
                       fixture.business_date::date, fixture.net_amount,
                       fixture.cost_amount, '00000000-0000-4000-8000-000000000773'
                FROM integration_connections connection
                CROSS JOIN (VALUES
                    ('00000000-0000-4000-8000-000000000774', 'charger-sale-a',
                     '00000000-0000-4000-8000-000000000771',
                     'SALE', '2026-09-02T10:00:00Z', '2026-09-02', 400, 200),
                    ('00000000-0000-4000-8000-000000000775', 'charger-sale-b',
                     '00000000-0000-4000-8000-000000000772',
                     'SALE', '2026-09-02T11:00:00Z', '2026-09-02', 500, 250),
                    ('00000000-0000-4000-8000-000000000776', 'charger-return-a',
                     '00000000-0000-4000-8000-000000000771',
                     'RETURN', '2026-09-03T10:00:00Z', '2026-09-03', 100, 50)
                ) fixture(id, external_id, store_id, kind, occurred_at,
                          business_date, net_amount, cost_amount)
                WHERE connection.connection_key = 'livesklad-default'
                """);
        execute("""
                INSERT INTO sales_document_items (
                    sales_document_id, external_id, product_id, product_name_snapshot,
                    analytics_category_id, classification_version, condition_type_snapshot,
                    quantity, unit_price, gross_amount, discount_amount,
                    net_amount, cost_amount, cost_quality, is_work
                )
                SELECT CASE WHEN product.code IN ('3481', '3480', '4013', '4350')
                            THEN '00000000-0000-4000-8000-000000000774'::uuid
                            ELSE '00000000-0000-4000-8000-000000000775'::uuid END,
                       'sale-' || product.code, product.id, product.name,
                       category.id, 'fixture-v1', 'NEW',
                       1, 100, 100, 0, 100, 50, 'KNOWN', false
                FROM products product
                JOIN analytics_categories category ON category.code =
                    CASE WHEN product.code IN ('3390', '3391')
                             THEN 'CHARGER_CABLE'
                         WHEN product.code = '4350'
                             THEN 'PODS_WATCH_OTHER_DEVICE'
                         ELSE 'OTHER_ACCESSORY_PRODUCT' END
                """);
        execute("""
                INSERT INTO sales_document_items (
                    sales_document_id, external_id, product_id, product_name_snapshot,
                    analytics_category_id, classification_version, condition_type_snapshot,
                    quantity, unit_price, gross_amount, discount_amount,
                    net_amount, cost_amount, cost_quality, is_work
                )
                SELECT '00000000-0000-4000-8000-000000000776',
                       'return-4350', sale.product_id, sale.product_name_snapshot,
                       sale.analytics_category_id, 'fixture-v1', 'NEW',
                       1, 100, 100, 0, 100, 50, 'KNOWN', false
                FROM sales_document_items sale
                WHERE sale.external_id = 'sale-4350'
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String query(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
