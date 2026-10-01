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
class HeadphoneCategoryMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void preservesHistoricalRowsDuringProspectiveSchemaPreparation() throws SQLException {
        flyway("61").migrate();
        addFixtures();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var before = HistoricalCatalogRows.snapshot(connection);
            flyway("62").migrate();
            assertThat(HistoricalCatalogRows.snapshot(connection)).isEqualTo(before);
            flyway("90").migrate();
            assertThat(HistoricalCatalogRows.snapshot(connection)).isEqualTo(before);
        }
    }

    private void assertCategory(String code, String expected) throws SQLException {
        assertThat(query("""
                SELECT category.code
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                JOIN analytics_categories category ON category.id = assignment.analytics_category_id
                WHERE product.code = '%s'
                """.formatted(code))).isEqualTo(expected);
        assertThat(query("""
                SELECT category.code
                FROM sales_document_items item
                JOIN products product ON product.id = item.product_id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE product.code = '%s'
                """.formatted(code))).isEqualTo(expected);
    }

    private String roles(String view) throws SQLException {
        return query("SELECT string_agg(device_role, ',' ORDER BY device_role) FROM " + view);
    }

    private void addFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (id, connection_id, source_system, external_id, name)
                    SELECT '00000000-0000-4000-8000-000000000621',
                           id, 'LIVESKLAD', 'headphone-test-store', 'Headphone store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000622',
                           id, '00000000-0000-4000-8000-000000000621',
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
                        ('1788', 'Airpods 4 ANC NEW'),
                        ('5537', 'Apple EarPods (Lightning) A1748'),
                        ('3571', 'Samsung Galaxy Buds 4 Black'),
                        ('3636', 'Marshall Major 5 Black'),
                        ('4256', 'Sony WF-1000XM6 Black New'),
                        ('9988', 'Apple Watch Series 11'),
                        ('9989', 'Чехол AirPods 4')
                    ) fixture(code, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from, change_reason
                    )
                    SELECT product.id, category.id, 'NEW', 'INITIAL_IMPORT',
                           'fixture-v1', '2026-09-01T00:00:00Z', 'Fixture'
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = CASE WHEN product.code = '9989'
                                              THEN 'ACCESSORY_PODS_WATCH'
                                              ELSE 'PODS_WATCH_OTHER_DEVICE' END
                    WHERE product.code IN (
                        '1788', '5537', '3571', '3636', '4256', '9988', '9989'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000623',
                           id, 'LIVESKLAD', 'headphone-test-sale',
                           '00000000-0000-4000-8000-000000000621',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 700, 350,
                           '00000000-0000-4000-8000-000000000622'
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
                    SELECT '00000000-0000-4000-8000-000000000623',
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'fixture-v1', 'NEW',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = CASE WHEN product.code = '9989'
                                              THEN 'ACCESSORY_PODS_WATCH'
                                              ELSE 'PODS_WATCH_OTHER_DEVICE' END
                    WHERE product.code IN (
                        '1788', '5537', '3571', '3636', '4256', '9988', '9989'
                    )
                    """);
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
