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
class XCrystalIphoneCaseMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void preservesHistoricalRowsDuringProspectiveSchemaPreparation() throws SQLException {
        flyway("63").migrate();
        addFixtures();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var before = HistoricalCatalogRows.snapshot(connection);
            flyway("64").migrate();
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
                    SELECT '00000000-0000-4000-8000-000000000641',
                           id, 'LIVESKLAD', 'x-crystal-test-store', 'X-Crystal test store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000642',
                           id, '00000000-0000-4000-8000-000000000641',
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
                        ('2552', 'Чехол Keephone X-Crystal 14 Pro'),
                        ('4122', 'Чехол Keephone X-Crystal 15 Pro Max'),
                        ('3421', 'Чехол Keephone X-Crystal 15 Pro Max Black'),
                        ('3420', 'Чехол Keephone X-Crystal 15 Pro Max Clear'),
                        ('2861', 'Чехол Keephone X-Crystal 16'),
                        ('2550', 'Чехол Keephone X-Crystal 16 Pro'),
                        ('2551', 'Чехол Keephone X-Crystal 16 Pro Max'),
                        ('2549', 'Чехол Keephone X-Crystal 17'),
                        ('2545', 'Чехол Keephone X-Crystal 17 Pro'),
                        ('2621', 'Чехол Keephone X-Crystal 17 Pro Clear'),
                        ('2547', 'Чехол Keephone X-Crystal 17 Pro Max'),
                        ('2548', 'Чехол Keephone X-Crystal 17 Pro Max Clear'),
                        ('2623', 'Чехол Keephone X-Crystal 17 Pro Max Black'),
                        ('2546', 'Чехол Keephone X-Crystal 17 Pro Blue'),
                        ('39', 'Чехол Keephone X-Crystal'),
                        ('3217', 'Чехол Keephone X-Crystal Samsung S25')
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
                    WHERE product.external_id IN (
                        '2552', '4122', '3421', '3420', '2861', '2550', '2551',
                        '2549', '2545', '2621', '2547', '2548', '2623', '2546',
                        '39', '3217'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000643',
                           id, 'LIVESKLAD', 'x-crystal-test-sale',
                           '00000000-0000-4000-8000-000000000641',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 1600, 800,
                           '00000000-0000-4000-8000-000000000642'
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
                    SELECT '00000000-0000-4000-8000-000000000643',
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'fixture-v1', 'NOT_APPLICABLE',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = 'OTHER_ACCESSORY_PRODUCT'
                    WHERE product.external_id IN (
                        '2552', '4122', '3421', '3420', '2861', '2550', '2551',
                        '2549', '2545', '2621', '2547', '2548', '2623', '2546',
                        '39', '3217'
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
