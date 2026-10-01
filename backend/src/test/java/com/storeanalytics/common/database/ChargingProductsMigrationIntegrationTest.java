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
class ChargingProductsMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void preservesHistoricalRowsDuringProspectiveSchemaPreparation() throws SQLException {
        flyway("73").migrate();
        addFixtures();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var before = HistoricalCatalogRows.snapshot(connection);
            flyway("75").migrate();
            assertThat(HistoricalCatalogRows.snapshot(connection)).isEqualTo(before);
            flyway("90").migrate();
            assertThat(HistoricalCatalogRows.snapshot(connection)).isEqualTo(before);
        }
    }

    private void addFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (id, connection_id, source_system, external_id, name)
                    SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                           fixture.external_id, fixture.name
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000741', 'charger-store-a', 'A'),
                        ('00000000-0000-4000-8000-000000000742', 'charger-store-b', 'B')
                    ) fixture(id, external_id, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000743',
                           id, '00000000-0000-4000-8000-000000000741',
                           'LIVESKLAD', 'MANUAL', 'SALES', 'SUCCESS',
                           '2026-09-01T10:00:00Z', '2026-09-01T10:01:00Z'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO products (
                        connection_id, source_system, external_id, code, name, source_kind
                    )
                    SELECT connection.id, 'LIVESKLAD', fixture.code,
                           fixture.code, fixture.name, 'PRODUCT'
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('4767', 'Apple Power Adapter 30W Original'),
                        ('4768', 'No Box Apple Cable USB-C to USB-C 60W'),
                        ('4769', 'Samsung Power Adapter 25W Original'),
                        ('324', 'Блок Baseus 30w Speed Mini Белый'),
                        ('1941', 'Блок Baseus 30w Speed Mini Черный'),
                        ('3241', 'Блок Baseus 45w EnerCore CJ11 Черный'),
                        ('3494', 'Блок Baseus 65W Fast Charger'),
                        ('3493', 'Блок Baseus GAN 67W Fast Charger'),
                        ('47', 'Блок Baseus Type-c 20W Speed Mini белый'),
                        ('48', 'Блок Baseus Type-c 20W Speed Mini Черный'),
                        ('64', 'Комплект Baseus 20w  Белый Type-c'),
                        ('65', 'Комплект Baseus 20w  Белый Lightning'),
                        ('66', 'Комплект Baseus 20w  Черный Type-c'),
                        ('67', 'Комплект Baseus 20w  Черный Lightning'),
                        ('690', 'Комплект Baseus Gan5 30w Type-c (с кабелем) White'),
                        ('9000', 'Samsung Power Adapter 25W Original')
                    ) fixture(code, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from
                    )
                    SELECT product.id, category.id, 'NEW',
                           CASE WHEN product.code = '4769' THEN 'MANUAL' ELSE 'AUTO' END,
                           'fixture-v1', '2026-09-01T00:00:00Z'
                    FROM products product
                    JOIN analytics_categories category ON category.code =
                        CASE WHEN product.code IN ('4769', '9000') THEN 'SAMSUNG_NEW'
                             WHEN product.code = '3241' THEN 'CHARGER_CABLE'
                             ELSE 'UNMAPPED' END
                    WHERE product.code <> '47'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                           fixture.external_id, fixture.store_id::uuid,
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', fixture.net_amount, fixture.cost_amount,
                           '00000000-0000-4000-8000-000000000743'
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000744', 'charger-sale-a',
                         '00000000-0000-4000-8000-000000000741', 600, 300),
                        ('00000000-0000-4000-8000-000000000745', 'charger-sale-b',
                         '00000000-0000-4000-8000-000000000742', 1000, 500)
                    ) fixture(id, external_id, store_id, net_amount, cost_amount)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        sales_document_id, external_id, product_id,
                        product_name_snapshot, analytics_category_id,
                        classification_version, condition_type_snapshot,
                        quantity, unit_price, gross_amount, discount_amount,
                        net_amount, cost_amount, cost_quality, is_work
                    )
                    SELECT CASE WHEN product.code IN
                                    ('4767', '4768', '4769', '324', '1941', '9000')
                                THEN '00000000-0000-4000-8000-000000000744'::uuid
                                ELSE '00000000-0000-4000-8000-000000000745'::uuid END,
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'fixture-v1', 'NEW',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category ON category.code =
                        CASE WHEN product.code IN ('4769', '9000') THEN 'SAMSUNG_NEW'
                             WHEN product.code = '3241' THEN 'CHARGER_CABLE'
                             ELSE 'UNMAPPED' END
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        original_document_id, document_kind, source_document_type,
                        occurred_at, business_date, net_amount, cost_amount,
                        last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000746',
                           id, 'LIVESKLAD', 'charger-return-a',
                           '00000000-0000-4000-8000-000000000741',
                           '00000000-0000-4000-8000-000000000744',
                           'RETURN', 'RETURN', '2026-09-03T10:00:00Z',
                           '2026-09-03', 100, 50,
                           '00000000-0000-4000-8000-000000000743'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        sales_document_id, external_id, original_item_id, product_id,
                        product_name_snapshot, analytics_category_id,
                        classification_version, condition_type_snapshot,
                        quantity, unit_price, gross_amount, discount_amount,
                        net_amount, cost_amount, cost_quality, is_work
                    )
                    SELECT '00000000-0000-4000-8000-000000000746',
                           'return-4769', sale.id, sale.product_id,
                           sale.product_name_snapshot, sale.analytics_category_id,
                           'fixture-v1', 'NEW', 1, 100, 100, 0, 100, 50,
                           'KNOWN', false
                    FROM sales_document_items sale
                    WHERE sale.external_id = 'sale-4769'
                    """);
        }
    }

    private String category(String table, String code) throws SQLException {
        return query("SELECT category.code FROM " + table + " entry "
                + "JOIN products product ON product.id = entry.product_id "
                + "JOIN analytics_categories category ON category.id = entry.analytics_category_id "
                + "WHERE product.code = '" + code + "' LIMIT 1");
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
