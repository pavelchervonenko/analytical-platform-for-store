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
class SpeakerCategoryMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void movesOnlyApprovedSpeakersAndPreservesAmountsAndPayroll()
            throws SQLException {
        flyway("55").migrate();
        addFixtures();

        flyway("56").migrate();

        assertThat(query("""
                SELECT version
                FROM flyway_schema_history
                WHERE success
                ORDER BY installed_rank DESC
                LIMIT 1
                """)).isEqualTo("56");
        assertThat(query("""
                SELECT category_kind || '|' || device_family || '|' ||
                       counts_as_phone || '|' || counts_as_device || '|' ||
                       payroll_category_code
                FROM analytics_categories
                WHERE code = 'SPEAKERS'
                """)).isEqualTo("DEVICE|OTHER|false|true|TECH_TIER_2");
        assertThat(assignmentCategory("4300")).isEqualTo("SPEAKERS");
        assertThat(itemCategory("4300", "SALE")).isEqualTo("SPEAKERS");
        assertThat(itemCategory("4300", "RETURN")).isEqualTo("SPEAKERS");
        assertThat(itemCategory("5312", "SALE")).isEqualTo("SPEAKERS");
        assertThat(itemCategory("7000", "SALE"))
                .isEqualTo("PODS_WATCH_OTHER_DEVICE");
        assertThat(query("""
                SELECT count(*)::text
                FROM sales_document_items item
                JOIN product_category_assignments assignment
                  ON assignment.id = item.category_assignment_id
                JOIN products product ON product.id = item.product_id
                WHERE product.code = '4300'
                  AND item.analytics_category_id = assignment.analytics_category_id
                """)).isEqualTo("2");
        assertThat(query("""
                SELECT sum(net_amount)::text || '|' || sum(cost_amount)::text
                FROM sales_document_items
                """)).isEqualTo("400.00|200.00");
    }

    private void addFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name
                    )
                    SELECT '00000000-0000-4000-8000-000000000561',
                           id, 'LIVESKLAD', 'speaker-test-store',
                           'Speaker test store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000562',
                           id, '00000000-0000-4000-8000-000000000561',
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
                        ('4300', 'Колонка JBL Flip 7 Black New'),
                        ('5312', 'Яндекс Станция Макс бежевый'),
                        ('7000', 'Наушники JBL Tune 770NC Black')
                    ) fixture(code, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from, change_reason
                    )
                    SELECT product.id, category.id, 'NEW', 'INITIAL_IMPORT',
                           'speaker-fixture-v1', '2025-12-31T22:00:00Z',
                           'Previous category fixture'
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = 'PODS_WATCH_OTHER_DEVICE'
                    WHERE product.code = '4300'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000563',
                           id, 'LIVESKLAD', 'speaker-test-sale',
                           '00000000-0000-4000-8000-000000000561',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 300, 150,
                           '00000000-0000-4000-8000-000000000562'
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
                    SELECT '00000000-0000-4000-8000-000000000563',
                           'sale-' || product.code, product.id, product.name,
                           category.id, assignment.id, 'speaker-fixture-v1',
                           'NEW', 1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = 'PODS_WATCH_OTHER_DEVICE'
                    LEFT JOIN product_category_assignments assignment
                      ON assignment.product_id = product.id
                    WHERE product.code IN ('4300', '5312', '7000')
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        original_document_id, document_kind, source_document_type,
                        occurred_at, business_date, net_amount, cost_amount,
                        last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000564',
                           id, 'LIVESKLAD', 'speaker-test-return',
                           '00000000-0000-4000-8000-000000000561',
                           '00000000-0000-4000-8000-000000000563',
                           'RETURN', 'RETURN', '2026-09-03T10:00:00Z',
                           '2026-09-03', 100, 50,
                           '00000000-0000-4000-8000-000000000562'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        sales_document_id, external_id, original_item_id,
                        product_id, product_name_snapshot, analytics_category_id,
                        category_assignment_id, classification_version,
                        condition_type_snapshot, quantity, unit_price,
                        gross_amount, discount_amount, net_amount, cost_amount,
                        cost_quality, is_work
                    )
                    SELECT '00000000-0000-4000-8000-000000000564',
                           'return-4300', original.id, original.product_id,
                           original.product_name_snapshot,
                           original.analytics_category_id,
                           original.category_assignment_id,
                           original.classification_version,
                           original.condition_type_snapshot,
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM sales_document_items original
                    WHERE original.external_id = 'sale-4300'
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
