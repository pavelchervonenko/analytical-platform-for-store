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
class Mago1516IphoneCaseMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void preservesHistoricalRowsDuringProspectiveSchemaPreparation() throws SQLException {
        flyway("66").migrate();
        addFixtures();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var before = HistoricalCatalogRows.snapshot(connection);
            flyway("67").migrate();
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
                    SELECT '00000000-0000-4000-8000-000000000671',
                           id, 'LIVESKLAD', 'mago-1516-test-store', 'Mago test store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000672',
                           id, '00000000-0000-4000-8000-000000000671',
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
                        ('3423', 'Чехол Keephone Mago Pro 15 Pro Max синий'),
                        ('3424', 'Чехол Keephone Mago Pro 15 Pro Max черный'),
                        ('3293', 'Чехол Keephone Mago Pro Matte Magsafe 15 Pro Black'),
                        ('3294', 'Чехол Keephone Mago Pro Matte Magsafe 15 Pro серый'),
                        ('3295', 'Чехол Keephone Mago Pro Matte Magsafe 15 Pro синий'),
                        ('2870', 'Чехол Keephone Mago Pro Matte Magsafe 16 Pro Max Desert'),
                        ('2871', 'Чехол Keephone Mago Pro Matte Magsafe 16 Pro Max Black'),
                        ('2872', 'Чехол Keephone Mago Pro Matte Magsafe 16 Pro Max Titanium'),
                        ('38', 'Чехол Keephone Mago Pro Matte Magsafe'),
                        ('9002', 'Чехол Keephone Mago Samsung S25')
                    ) fixture(code, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from
                    )
                    SELECT product.id, category.id, 'NOT_APPLICABLE',
                           'AUTO', 'fixture-v1', '2026-09-01T00:00:00Z'
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = 'OTHER_ACCESSORY_PRODUCT'
                    WHERE product.code IN (
                        '3423', '3424', '3293', '3294', '3295',
                        '2870', '2871', '2872', '38', '9002'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000673',
                           id, 'LIVESKLAD', 'mago-1516-test-sale',
                           '00000000-0000-4000-8000-000000000671',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 900, 450,
                           '00000000-0000-4000-8000-000000000672'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        sales_document_id, external_id, product_id,
                        product_name_snapshot, analytics_category_id,
                        classification_version, condition_type_snapshot,
                        quantity, unit_price, gross_amount, discount_amount,
                        net_amount, cost_amount, cost_quality, is_work
                    )
                    SELECT '00000000-0000-4000-8000-000000000673',
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'fixture-v1', 'NOT_APPLICABLE',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = 'OTHER_ACCESSORY_PRODUCT'
                    WHERE product.code IN (
                        '3424', '3293', '3294', '3295',
                        '2870', '2871', '2872', '38', '9002'
                    )
                    """);
        }
    }

    private String count(String table, String category) throws SQLException {
        return query("SELECT count(*)::text FROM " + table + " item "
                + "JOIN analytics_categories category ON category.id = item.analytics_category_id "
                + "WHERE category.code = '" + category + "'");
    }

    private String units(String view, String category) throws SQLException {
        return query("SELECT coalesce(sum(net_quantity), 0)::integer::text FROM "
                + view + " WHERE numerator_metric_code = '" + category + "'");
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
