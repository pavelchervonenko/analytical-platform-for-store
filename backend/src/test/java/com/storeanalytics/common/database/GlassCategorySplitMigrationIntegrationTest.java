package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class GlassCategorySplitMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void separatesScreenGlassFromCameraProtectionWithoutChangingAmounts()
            throws SQLException {
        flyway("35").migrate();
        addCombinedCategoryFixtures();

        flyway(null).migrate();

        assertThat(currentVersion()).isEqualTo("41");
        assertClassification("glass-iphone", "GLASS_IPHONE");
        assertClassification("camera-iphone", "GLASS_CAMERA_IPHONE");
        assertClassification("glass-samsung", "GLASS_SAMSUNG");
        assertClassification("camera-samsung", "GLASS_CAMERA_SAMSUNG");

        assertThat(categoryName("GLASS_IPHONE"))
                .isEqualTo("Защитное стекло iPhone");
        assertThat(categoryName("GLASS_CAMERA_IPHONE"))
                .isEqualTo("Защита камеры iPhone");
        assertThat(categoryName("GLASS_SAMSUNG"))
                .isEqualTo("Защитное стекло Samsung");
        assertThat(categoryName("GLASS_CAMERA_SAMSUNG"))
                .isEqualTo("Защита камеры Samsung");

        assertThat(payrollCategory("GLASS_IPHONE")).isEqualTo("ACCESSORY");
        assertThat(payrollCategory("GLASS_SAMSUNG")).isEqualTo("ACCESSORY");
        assertThat(payrollCategory("GLASS_CAMERA_IPHONE")).isEqualTo("ACCESSORY");
        assertThat(payrollCategory("GLASS_CAMERA_SAMSUNG")).isEqualTo("ACCESSORY");

        assertThat(itemAmount("net_amount")).isEqualByComparingTo("400.00");
        assertThat(itemAmount("cost_amount")).isEqualByComparingTo("40.00");
    }

    private void assertClassification(
            String externalProductId,
            String expectedCategory
    ) throws SQLException {
        assertThat(assignmentCategory(externalProductId))
                .isEqualTo(expectedCategory);
        assertThat(itemCategory(externalProductId))
                .isEqualTo(expectedCategory);
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

    private void addCombinedCategoryFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            addStoreFixture(statement);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system, trigger_type,
                        sync_scope, status, started_at, finished_at
                    )
                    SELECT
                        '00000000-0000-4000-8000-000000000361',
                        id,
                        '00000000-0000-4000-8000-000000000360',
                        'LIVESKLAD',
                        'MANUAL',
                        'SALES',
                        'SUCCESS',
                        '2026-08-01T10:00:00Z',
                        '2026-08-01T10:01:00Z'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO products (
                        id, connection_id, source_system, external_id, code, name,
                        source_kind
                    )
                    SELECT fixture.id::uuid,
                           connection.id,
                           'LIVESKLAD',
                           fixture.external_id,
                           fixture.external_id,
                           fixture.product_name,
                           'PRODUCT'
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000362',
                         'glass-iphone',
                         'Защитное стекло Remax iPhone 15 Pro'),
                        ('00000000-0000-4000-8000-000000000363',
                         'camera-iphone',
                         'Защитное стекло на камеру Baseus Crystal iPhone 15'),
                        ('00000000-0000-4000-8000-000000000364',
                         'glass-samsung',
                         'Защитное стекло Remax S25'),
                        ('00000000-0000-4000-8000-000000000365',
                         'camera-samsung',
                         'Защита Kaмеры Keephone Samsung')
                    ) fixture(id, external_id, product_name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        id, product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from, change_reason
                    )
                    SELECT fixture.assignment_id::uuid,
                           product.id,
                           category.id,
                           'NOT_APPLICABLE',
                           'INITIAL_IMPORT',
                           'customer-approved-before-glass-split',
                           '2025-12-31T22:00:00Z',
                           'Combined glass fixture'
                    FROM (VALUES
                        ('00000000-0000-4000-8000-000000000366',
                         'glass-iphone', 'GLASS_CAMERA_IPHONE'),
                        ('00000000-0000-4000-8000-000000000367',
                         'camera-iphone', 'GLASS_CAMERA_IPHONE'),
                        ('00000000-0000-4000-8000-000000000368',
                         'glass-samsung', 'GLASS_CAMERA_SAMSUNG'),
                        ('00000000-0000-4000-8000-000000000369',
                         'camera-samsung', 'GLASS_CAMERA_SAMSUNG')
                    ) fixture(assignment_id, external_id, category_code)
                    JOIN products product
                      ON product.external_id = fixture.external_id
                    JOIN analytics_categories category
                      ON category.code = fixture.category_code
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT
                        '00000000-0000-4000-8000-000000000370',
                        id,
                        'LIVESKLAD',
                        'glass-sale',
                        '00000000-0000-4000-8000-000000000360',
                        'SALE',
                        'SALE',
                        '2026-08-01T10:00:00Z',
                        '2026-08-01',
                        400,
                        40,
                        '00000000-0000-4000-8000-000000000361'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        id, sales_document_id, external_id, product_id,
                        product_name_snapshot, analytics_category_id,
                        category_assignment_id, classification_version,
                        condition_type_snapshot, quantity, unit_price,
                        gross_amount, discount_amount, net_amount, cost_amount,
                        cost_quality, is_work
                    )
                    SELECT fixture.item_id::uuid,
                           '00000000-0000-4000-8000-000000000370',
                           fixture.external_id || '-item',
                           product.id,
                           product.name,
                           category.id,
                           assignment.id,
                           'customer-approved-before-glass-split',
                           'NOT_APPLICABLE',
                           1,
                           100,
                           100,
                           0,
                           100,
                           10,
                           'KNOWN',
                           false
                    FROM (VALUES
                        ('00000000-0000-4000-8000-000000000371',
                         'glass-iphone'),
                        ('00000000-0000-4000-8000-000000000372',
                         'camera-iphone'),
                        ('00000000-0000-4000-8000-000000000373',
                         'glass-samsung'),
                        ('00000000-0000-4000-8000-000000000374',
                         'camera-samsung')
                    ) fixture(item_id, external_id)
                    JOIN products product
                      ON product.external_id = fixture.external_id
                    JOIN product_category_assignments assignment
                      ON assignment.product_id = product.id
                    JOIN analytics_categories category
                      ON category.id = assignment.analytics_category_id
                    """);
        }
    }

    private void addStoreFixture(Statement statement) throws SQLException {
        statement.executeUpdate("""
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name
                )
                SELECT
                    '00000000-0000-4000-8000-000000000360',
                    id,
                    'LIVESKLAD',
                    'glass-migration-store',
                    'Glass migration store'
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """);
    }

    private String assignmentCategory(String externalProductId)
            throws SQLException {
        return queryString("""
                SELECT category.code
                FROM products product
                JOIN product_category_assignments assignment
                  ON assignment.product_id = product.id
                JOIN analytics_categories category
                  ON category.id = assignment.analytics_category_id
                WHERE product.external_id = ?
                """, externalProductId);
    }

    private String itemCategory(String externalProductId) throws SQLException {
        return queryString("""
                SELECT category.code
                FROM products product
                JOIN sales_document_items item ON item.product_id = product.id
                JOIN analytics_categories category
                  ON category.id = item.analytics_category_id
                WHERE product.external_id = ?
                """, externalProductId);
    }

    private String categoryName(String categoryCode) throws SQLException {
        return queryString("""
                SELECT name
                FROM analytics_categories
                WHERE code = ?
                """, categoryCode);
    }

    private String payrollCategory(String categoryCode) throws SQLException {
        return queryString("""
                SELECT payroll_category_code
                FROM analytics_categories
                WHERE code = ?
                """, categoryCode);
    }

    private String queryString(String sql, String value) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private BigDecimal itemAmount(String column) throws SQLException {
        String sql = "SELECT sum(" + column + ") FROM sales_document_items";
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getBigDecimal(1);
        }
    }

    private String currentVersion() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT version
                     FROM flyway_schema_history
                     WHERE success
                     ORDER BY installed_rank DESC
                     LIMIT 1
                     """)) {
            assertThat(result.next()).isTrue();
            return result.getString("version");
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}
