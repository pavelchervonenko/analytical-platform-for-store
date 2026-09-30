package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
class SmartGlassesCategoryMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void correctsAllRayBanGlassesAndUpdatesAttachDeviceTotals()
            throws SQLException {
        flyway("57").migrate();
        addFixtures();
        String attachV3Before = attachDeviceUnits("attach_rate_item_facts_v3");
        String attachV4Before = attachDeviceUnits("attach_rate_item_facts_v4");

        flyway("58").migrate();

        assertThat(query("""
                SELECT version FROM flyway_schema_history
                WHERE success ORDER BY installed_rank DESC LIMIT 1
                """)).isEqualTo("58");
        assertThat(query("""
                SELECT category_kind || '|' || device_family || '|' ||
                       counts_as_phone || '|' || counts_as_device || '|' ||
                       counts_as_additional_revenue || '|' || payroll_category_code
                FROM analytics_categories WHERE code = 'SMART_GLASSES'
                """)).isEqualTo("DEVICE|OTHER|false|true|false|TECH_TIER_2");
        assertThat(assignmentCategory("4308")).isEqualTo("SMART_GLASSES");
        assertThat(assignmentCategory("5558")).isEqualTo("SMART_GLASSES");
        assertThat(itemCategory("4308", "SALE")).isEqualTo("SMART_GLASSES");
        assertThat(itemCategory("5558", "SALE")).isEqualTo("SMART_GLASSES");
        assertThat(itemCategory("5558", "RETURN")).isEqualTo("SMART_GLASSES");
        assertThat(assignmentCategory("7000")).isEqualTo("SMART_GLASSES");
        assertThat(assignmentCategory("7002")).isEqualTo("SMART_GLASSES");
        assertThat(query("""
                SELECT assignment.condition_type
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                WHERE product.code = '7002'
                """)).isEqualTo("USED");
        assertThat(itemCategory("7000", "SALE")).isEqualTo("SMART_GLASSES");
        assertThat(itemCategory("7001", "SALE")).isEqualTo("PODS_WATCH_OTHER_DEVICE");
        assertThat(new BigDecimal(attachDeviceUnits("attach_rate_item_facts_v3"))
                .subtract(new BigDecimal(attachV3Before)))
                .isEqualByComparingTo("1");
        assertThat(new BigDecimal(attachDeviceUnits("attach_rate_item_facts_v4"))
                .subtract(new BigDecimal(attachV4Before)))
                .isEqualByComparingTo("1");
        assertThat(query("""
                SELECT sum(net_amount)::text || '|' || sum(cost_amount)::text
                FROM sales_document_items
                """)).isEqualTo("500.00|250.00");
        assertThat(query("""
                SELECT resolve_default_payroll_category(
                    'SMART_GLASSES', 'Ray Ban Meta Starfire', 'TECH_TIER_2'
                )
                """)).isEqualTo("TECH_TIER_2");
    }

    private String attachDeviceUnits(String view) throws SQLException {
        return query("""
                SELECT COALESCE(sum(net_quantity), 0)::numeric(19, 3)::text
                FROM %s
                WHERE store_id = '00000000-0000-4000-8000-000000000581'
                  AND device_role = 'OTHER_DEVICE'
                """.formatted(view));
    }

    private void addFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name
                    )
                    SELECT '00000000-0000-4000-8000-000000000581',
                           id, 'LIVESKLAD', 'glasses-test-store', 'Glasses store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000582',
                           id, '00000000-0000-4000-8000-000000000581',
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
                        ('4308', 'Ray Ban Wayfarer Matt Black Polar Gradient Graphite (L) NEW'),
                        ('5558', 'Ray-Ban Meta Starfire Kylie Black/Clear to Grey Transition'),
                        ('7000', 'Ray Ban Wayfarer ordinary sunglasses'),
                        ('7001', 'Ray Ban protective case'),
                        ('7002', 'Ray Ban Wayfarer Б/У')
                    ) fixture(code, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from, change_reason
                    )
                    SELECT product.id, category.id, 'NEW', 'INITIAL_IMPORT',
                           'glasses-fixture-v1', '2025-12-31T22:00:00Z',
                           'Previous category fixture'
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = CASE WHEN product.code = '7000'
                          THEN 'UNMAPPED' ELSE 'PODS_WATCH_OTHER_DEVICE'
                         END
                    WHERE product.code IN ('4308', '7000', '7001')
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000583',
                           id, 'LIVESKLAD', 'glasses-test-sale',
                           '00000000-0000-4000-8000-000000000581',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 400, 200,
                           '00000000-0000-4000-8000-000000000582'
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
                    SELECT '00000000-0000-4000-8000-000000000583',
                           'sale-' || product.code, product.id, product.name,
                           category.id, assignment.id, 'glasses-fixture-v1',
                           'NEW', 1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = CASE WHEN product.code = '7000'
                          THEN 'UNMAPPED' ELSE 'PODS_WATCH_OTHER_DEVICE'
                         END
                    LEFT JOIN product_category_assignments assignment
                      ON assignment.product_id = product.id
                    WHERE product.code IN ('4308', '5558', '7000', '7001')
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        original_document_id, document_kind, source_document_type,
                        occurred_at, business_date, net_amount, cost_amount,
                        last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000584',
                           id, 'LIVESKLAD', 'glasses-test-return',
                           '00000000-0000-4000-8000-000000000581',
                           '00000000-0000-4000-8000-000000000583',
                           'RETURN', 'RETURN', '2026-09-03T10:00:00Z',
                           '2026-09-03', 100, 50,
                           '00000000-0000-4000-8000-000000000582'
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
                    SELECT '00000000-0000-4000-8000-000000000584',
                           'return-5558', original.id, original.product_id,
                           original.product_name_snapshot,
                           original.analytics_category_id,
                           original.classification_version,
                           original.condition_type_snapshot,
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM sales_document_items original
                    WHERE original.external_id = 'sale-5558'
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
