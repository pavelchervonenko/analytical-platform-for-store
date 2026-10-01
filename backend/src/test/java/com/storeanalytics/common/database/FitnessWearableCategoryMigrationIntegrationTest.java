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
class FitnessWearableCategoryMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void preservesHistoricalRowsDuringProspectiveSchemaPreparation() throws SQLException {
        flyway("56").migrate();
        addFixtures();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var before = HistoricalCatalogRows.snapshot(connection);
            flyway("57").migrate();
            assertThat(HistoricalCatalogRows.snapshot(connection)).isEqualTo(before);
            flyway("90").migrate();
            assertThat(HistoricalCatalogRows.snapshot(connection)).isEqualTo(before);
        }
    }

    private void addFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name
                    )
                    SELECT '00000000-0000-4000-8000-000000000571',
                           id, 'LIVESKLAD', 'fitness-test-store', 'Fitness store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000572',
                           id, '00000000-0000-4000-8000-000000000571',
                           'LIVESKLAD', 'MANUAL', 'SALES', 'SUCCESS',
                           '2026-09-01T10:00:00Z', '2026-09-01T10:01:00Z'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO products (
                        connection_id, source_system, external_id,
                        code, name, source_kind
                    )
                    SELECT connection.id, 'LIVESKLAD', fixture.code,
                           fixture.code, fixture.name, 'PRODUCT'
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('5019', 'Браслет Google Fitbit Air Obsidian'),
                        ('5183', 'Браслет Whoop 5.0 Peak'),
                        ('9999', 'Apple Watch Test')
                    ) fixture(code, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from, change_reason
                    )
                    SELECT product.id, category.id, 'NEW', 'INITIAL_IMPORT',
                           'fitness-fixture-v1', '2025-12-31T22:00:00Z',
                           'Previous category fixture'
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = 'PODS_WATCH_OTHER_DEVICE'
                    WHERE product.code IN ('5019', '9999')
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000573',
                           id, 'LIVESKLAD', 'fitness-test-sale',
                           '00000000-0000-4000-8000-000000000571',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 300, 150,
                           '00000000-0000-4000-8000-000000000572'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        sales_document_id, external_id, product_id,
                        product_name_snapshot, analytics_category_id,
                        category_assignment_id, classification_version,
                        condition_type_snapshot, quantity, unit_price,
                        gross_amount, discount_amount, net_amount, cost_amount,
                        cost_quality, is_work
                    )
                    SELECT '00000000-0000-4000-8000-000000000573',
                           'sale-' || product.code, product.id, product.name,
                           category.id, assignment.id, 'fitness-fixture-v1',
                           'NEW', 1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = 'PODS_WATCH_OTHER_DEVICE'
                    LEFT JOIN product_category_assignments assignment
                      ON assignment.product_id = product.id
                    WHERE product.code IN ('5019', '5183', '9999')
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        original_document_id, document_kind, source_document_type,
                        occurred_at, business_date, net_amount, cost_amount,
                        last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000574',
                           id, 'LIVESKLAD', 'fitness-test-return',
                           '00000000-0000-4000-8000-000000000571',
                           '00000000-0000-4000-8000-000000000573',
                           'RETURN', 'RETURN', '2026-09-03T10:00:00Z',
                           '2026-09-03', 100, 50,
                           '00000000-0000-4000-8000-000000000572'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        sales_document_id, external_id, original_item_id,
                        product_id, product_name_snapshot, analytics_category_id,
                        classification_version, condition_type_snapshot, quantity,
                        unit_price, gross_amount, discount_amount, net_amount,
                        cost_amount, cost_quality, is_work
                    )
                    SELECT '00000000-0000-4000-8000-000000000574',
                           'return-5183', original.id, original.product_id,
                           original.product_name_snapshot,
                           original.analytics_category_id,
                           original.classification_version,
                           original.condition_type_snapshot,
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM sales_document_items original
                    WHERE original.external_id = 'sale-5183'
                    """);
        }
    }

    private String assignmentCategory(String code) throws SQLException {
        return query("""
                SELECT category.code
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                JOIN analytics_categories category
                  ON category.id = assignment.analytics_category_id
                WHERE product.code = '%s'
                """.formatted(code));
    }

    private String itemCategory(String code, String documentKind)
            throws SQLException {
        return query("""
                SELECT category.code
                FROM sales_document_items item
                JOIN products product ON product.id = item.product_id
                JOIN sales_documents document
                  ON document.id = item.sales_document_id
                JOIN analytics_categories category
                  ON category.id = item.analytics_category_id
                WHERE product.code = '%s'
                  AND document.document_kind = '%s'
                """.formatted(code, documentKind));
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
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
