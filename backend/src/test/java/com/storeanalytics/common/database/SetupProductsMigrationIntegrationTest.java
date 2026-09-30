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
class SetupProductsMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void correctsApprovedProductBackedServicesWithoutChangingWorkFlag()
            throws SQLException {
        flyway("59").migrate();
        addFixtures();

        flyway("60").migrate();

        assertThat(query("""
                SELECT version FROM flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """)).isEqualTo("60");
        for (String code : new String[]{"6278", "6151", "5348"}) {
            assertThat(assignmentCategory(code)).isEqualTo("SETUP_SERVICE");
            assertThat(itemCategory(code)).isEqualTo("SETUP_SERVICE");
        }
        assertThat(query("""
                SELECT assignment.condition_type
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                WHERE product.code = '6278'
                """)).isEqualTo("NOT_APPLICABLE");
        assertThat(query("""
                SELECT item.condition_type_snapshot || '|' || item.is_work || '|' ||
                       product.source_kind
                FROM sales_document_items item
                JOIN products product ON product.id = item.product_id
                WHERE product.code = '6278'
                """)).isEqualTo("NOT_APPLICABLE|false|PRODUCT");
        assertThat(itemCategory("7777")).isEqualTo("UNMAPPED");
        assertThat(query("""
                SELECT count(*)::text
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                WHERE product.code = '7777'
                """)).isEqualTo("0");
        assertThat(query("""
                SELECT item.classification_version
                FROM sales_document_items item
                JOIN products product ON product.id = item.product_id
                WHERE product.code = '6151'
                """)).isEqualTo("setup-fixture-v1");
        assertThat(query("""
                SELECT sum(net_amount)::text || '|' || sum(cost_amount)::text
                FROM sales_document_items
                """)).isEqualTo("400.00|200.00");
        assertThat(query("""
                SELECT payroll_category_code
                FROM analytics_categories WHERE code = 'SETUP_SERVICE'
                """)).isEqualTo("SERVICE");
    }

    private void addFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name
                    )
                    SELECT '00000000-0000-4000-8000-000000000601',
                           id, 'LIVESKLAD', 'setup-products-test-store',
                           'Setup products store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000602',
                           id, '00000000-0000-4000-8000-000000000601',
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
                        ('6278', 'Восстановления паролей'),
                        ('6151', 'настройка Apple Watch'),
                        ('5348', 'Настройка РЕЖИМА МОДЕМА'),
                        ('7777', 'Восстановления паролей')
                    ) fixture(code, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000603',
                           id, 'LIVESKLAD', 'setup-products-test-sale',
                           '00000000-0000-4000-8000-000000000601',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 400, 200,
                           '00000000-0000-4000-8000-000000000602'
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
                    SELECT '00000000-0000-4000-8000-000000000603',
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'setup-fixture-v1',
                           CASE WHEN product.code IN ('6278', '7777')
                               THEN 'UNKNOWN' ELSE 'NOT_APPLICABLE' END,
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = CASE
                          WHEN product.code IN ('6278', '7777')
                              THEN 'UNMAPPED'
                          ELSE 'SETUP_SERVICE'
                         END
                    WHERE product.code IN ('6278', '6151', '5348', '7777')
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

    private String itemCategory(String code) throws SQLException {
        return query("""
                SELECT category.code
                FROM sales_document_items item
                JOIN products product ON product.id = item.product_id
                JOIN analytics_categories category
                  ON category.id = item.analytics_category_id
                WHERE product.code = '%s'
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
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
