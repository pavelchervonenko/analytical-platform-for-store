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
class CareClassificationMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void repairsCareAssignmentAndNormalizedSaleItem() throws SQLException {
        flyway("31").migrate();
        addEliteCareFixture();

        flyway(null).migrate();

        assertThat(currentVersion()).isEqualTo("33");
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT
                         assignment_category.code AS assignment_category,
                         assignment.rule_version,
                         item_category.code AS item_category,
                         item.classification_version,
                         protection.name AS protection_name
                     FROM products product
                     JOIN product_category_assignments assignment
                       ON assignment.product_id = product.id
                     JOIN analytics_categories assignment_category
                       ON assignment_category.id = assignment.analytics_category_id
                     JOIN sales_document_items item
                       ON item.category_assignment_id = assignment.id
                     JOIN analytics_categories item_category
                       ON item_category.id = item.analytics_category_id
                     CROSS JOIN analytics_categories protection
                     WHERE product.external_id = '4967'
                       AND protection.code = 'PREMIUM_PROTECTION'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("assignment_category"))
                    .isEqualTo("WARRANTY_GENERIC");
            assertThat(result.getString("item_category"))
                    .isEqualTo("WARRANTY_GENERIC");
            assertThat(result.getString("rule_version"))
                    .isEqualTo("customer-approved-2026-08-07-v2");
            assertThat(result.getString("classification_version"))
                    .isEqualTo("customer-approved-2026-08-07-v2");
            assertThat(result.getString("protection_name")).isEqualTo("Протекция");
        }
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

    private void addEliteCareFixture() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name
                    )
                    SELECT
                        '00000000-0000-4000-8000-000000000321',
                        id,
                        'LIVESKLAD',
                        'care-migration-store',
                        'Care migration store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system, trigger_type,
                        sync_scope, status, started_at, finished_at
                    )
                    SELECT
                        '00000000-0000-4000-8000-000000000322',
                        id,
                        '00000000-0000-4000-8000-000000000321',
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
                    SELECT
                        '00000000-0000-4000-8000-000000000323',
                        id,
                        'LIVESKLAD',
                        '4967',
                        '4967',
                        'Моби Сфера ELITE CARE',
                        'SERVICE'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        id, product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from, change_reason
                    )
                    SELECT
                        '00000000-0000-4000-8000-000000000324',
                        '00000000-0000-4000-8000-000000000323',
                        id,
                        'NOT_APPLICABLE',
                        'INITIAL_IMPORT',
                        'customer-approved-2026-07-20-v1',
                        '2025-12-31T22:00:00Z',
                        'Initial customer-approved classification'
                    FROM analytics_categories
                    WHERE code = 'PREMIUM_PROTECTION'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT
                        '00000000-0000-4000-8000-000000000325',
                        id,
                        'LIVESKLAD',
                        'care-sale',
                        '00000000-0000-4000-8000-000000000321',
                        'SALE',
                        'SALE',
                        '2026-08-01T10:00:00Z',
                        '2026-08-01',
                        1000,
                        0,
                        '00000000-0000-4000-8000-000000000322'
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
                    SELECT
                        '00000000-0000-4000-8000-000000000326',
                        '00000000-0000-4000-8000-000000000325',
                        'care-sale-item',
                        '00000000-0000-4000-8000-000000000323',
                        'Моби Сфера ELITE CARE',
                        category.id,
                        '00000000-0000-4000-8000-000000000324',
                        'customer-approved-2026-07-20-v1',
                        'NOT_APPLICABLE',
                        1,
                        1000,
                        1000,
                        0,
                        1000,
                        0,
                        'ZERO_SERVICE',
                        true
                    FROM analytics_categories category
                    WHERE category.code = 'PREMIUM_PROTECTION'
                    """);
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
