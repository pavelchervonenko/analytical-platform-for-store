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
class CameraGlassCorrectionMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void preservesHistoricalRowsDuringProspectiveSchemaPreparation() throws SQLException {
        flyway("62").migrate();
        addFixtures();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var before = HistoricalCatalogRows.snapshot(connection);
            flyway("63").migrate();
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
                    SELECT '00000000-0000-4000-8000-000000000631',
                           id, 'LIVESKLAD', 'camera-glass-test-store', 'Camera glass store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000632',
                           id, '00000000-0000-4000-8000-000000000631',
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
                        ('5716', 'Защитное стекло для камеры VLP iPhone Air'),
                        ('5162', 'Защитные линзы Camera Film'),
                        ('9001', 'Защитное стекло iPhone 17 Pro'),
                        ('9002', 'Защита камер Samsung S25')
                    ) fixture(code, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000633',
                           id, 'LIVESKLAD', 'camera-glass-test-sale',
                           '00000000-0000-4000-8000-000000000631',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 500, 250,
                           '00000000-0000-4000-8000-000000000632'
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
                    SELECT '00000000-0000-4000-8000-000000000633',
                           fixture.sale_code, product.id, product.name,
                           category.id, 'fixture-v1', 'NOT_APPLICABLE',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM (VALUES
                        ('sale-5716-a', '5716', 'GLASS_IPHONE'),
                        ('sale-5716-b', '5716', 'GLASS_IPHONE'),
                        ('sale-5162', '5162', 'GLASS_CAMERA_IPHONE'),
                        ('sale-9001', '9001', 'GLASS_IPHONE'),
                        ('sale-9002', '9002', 'GLASS_CAMERA_SAMSUNG')
                    ) fixture(sale_code, product_code, category_code)
                    JOIN products product ON product.code = fixture.product_code
                    JOIN analytics_categories category ON category.code = fixture.category_code
                    """);
        }
    }

    private String units(String view, String category) throws SQLException {
        return query("""
                SELECT coalesce(sum(net_quantity), 0)::integer::text
                FROM %s
                WHERE numerator_metric_code = '%s'
                """.formatted(view, category));
    }

    private String assignmentCategory(String code) throws SQLException {
        return query("""
                SELECT category.code
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                JOIN analytics_categories category ON category.id = assignment.analytics_category_id
                WHERE product.code = '%s'
                """.formatted(code));
    }

    private String itemCategory(String code) throws SQLException {
        return query("""
                SELECT category.code
                FROM sales_document_items item
                JOIN products product ON product.id = item.product_id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE product.code = '%s'
                LIMIT 1
                """.formatted(code));
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
