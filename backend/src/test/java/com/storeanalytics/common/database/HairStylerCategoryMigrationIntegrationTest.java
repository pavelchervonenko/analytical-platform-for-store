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
class HairStylerCategoryMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void correctsFourApprovedCardsAndHistoricalSalesOnly() throws SQLException {
        flyway("60").migrate();
        addFixtures();

        flyway("61").migrate();

        assertThat(query("""
                SELECT category_kind || '|' || device_family || '|' ||
                       counts_as_device || '|' || payroll_category_code
                FROM analytics_categories WHERE code = 'HAIR_STYLERS'
                """)).isEqualTo("DEVICE|OTHER|true|TECH_TIER_1");
        assertThat(query("""
                SELECT resolve_default_payroll_category(
                    'HAIR_STYLERS', 'Dyson HS08', 'TECH_TIER_1'
                )
                """)).isEqualTo("TECH_TIER_1");
        assertThat(query("""
                SELECT count(*)::text FROM attach_rate_ordinary_item_facts_v4
                WHERE device_role = 'OTHER_DEVICE'
                """)).isEqualTo("5");
        for (String code : new String[]{"3105", "3183", "4282", "5201"}) {
            assertThat(assignmentCategory(code)).isEqualTo("HAIR_STYLERS");
            assertThat(itemCategory(code)).isEqualTo("HAIR_STYLERS");
        }
        assertThat(query("""
                SELECT assignment.assignment_source || '|' || assignment.condition_type
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                WHERE product.code = '5201'
                """)).isEqualTo("MANUAL|NEW");
        assertThat(assignmentCategory("9999")).isEqualTo("PODS_WATCH_OTHER_DEVICE");
        assertThat(itemCategory("9999")).isEqualTo("PODS_WATCH_OTHER_DEVICE");
        assertThat(query("""
                SELECT sum(net_amount)::text || '|' || sum(cost_amount)::text
                FROM sales_document_items
                """)).isEqualTo("500.00|250.00");
        assertThat(query("""
                SELECT count(*)::text
                FROM sales_document_items item
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE category.code = 'HAIR_STYLERS'
                """)).isEqualTo("4");
    }

    private void addFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (id, connection_id, source_system, external_id, name)
                    SELECT '00000000-0000-4000-8000-000000000611',
                           id, 'LIVESKLAD', 'hair-styler-test-store', 'Hair styler store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000612',
                           id, '00000000-0000-4000-8000-000000000611',
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
                        ('3105', 'Dyson HS08 LONG BLUE/COOPER'),
                        ('3183', 'Dyson hs08 Long Defuse Blue Cooper'),
                        ('4282', 'Стайлер Dyson HS08 красный бархат'),
                        ('5201', 'Стайлер Dyson Airwrap Co-Anda 2x'),
                        ('9999', 'Dyson vacuum cleaner')
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
                      ON category.code = CASE WHEN product.code = '3183'
                                              THEN 'IPAD_MAC'
                                              ELSE 'PODS_WATCH_OTHER_DEVICE' END
                    WHERE product.code IN ('3105', '3183', '4282', '9999')
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000613',
                           id, 'LIVESKLAD', 'hair-styler-test-sale',
                           '00000000-0000-4000-8000-000000000611',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 500, 250,
                           '00000000-0000-4000-8000-000000000612'
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
                    SELECT '00000000-0000-4000-8000-000000000613',
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'fixture-v1', 'NEW',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = CASE WHEN product.code = '3183'
                                              THEN 'IPAD_MAC'
                                              ELSE 'PODS_WATCH_OTHER_DEVICE' END
                    WHERE product.code IN ('3105', '3183', '4282', '5201', '9999')
                    """);
        }
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
