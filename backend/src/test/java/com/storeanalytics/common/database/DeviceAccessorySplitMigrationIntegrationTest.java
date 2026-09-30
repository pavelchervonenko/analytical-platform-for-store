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
class DeviceAccessorySplitMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void splitsExplicitTargetsWithoutChangingAmountsOrPhoneCaseMetric()
            throws SQLException {
        flyway("64").migrate();
        addFixtures();
        assertThat(units("attach_rate_item_facts_v3", "ACCESSORY_IPAD"))
                .isEqualTo("3");

        flyway("65").migrate();

        assertCategory("2470", "ACCESSORY_IPAD");
        assertCategory("5844", "ACCESSORY_IPAD");
        assertCategory("6175", "ACCESSORY_IPAD");
        assertCategory("2579", "ACCESSORY_IPAD");
        assertCategory("3325", "ACCESSORY_IPAD");
        assertCategory("3901", "ACCESSORY_IPAD");
        assertCategory("3784", "ACCESSORY_IPAD");
        assertCategory("2591", "ACCESSORY_MAC");
        assertCategory("2972", "ACCESSORY_MAC");
        assertCategory("2973", "ACCESSORY_MAC");
        assertCategory("4972", "ACCESSORY_MAC");
        assertCategory("5051", "CASE_OTHER_DEVICE");
        assertCategory("3628", "OTHER_ACCESSORY_PRODUCT");
        assertCategory("39", "OTHER_ACCESSORY_PRODUCT");
        for (String view : new String[]{
                "attach_rate_item_facts_v3", "attach_rate_ordinary_item_facts_v4"
        }) {
            assertThat(units(view, "ACCESSORY_IPAD")).isEqualTo("7");
            assertThat(query("SELECT count(*)::text FROM " + view
                    + " WHERE classification_issue_code = "
                    + "'IPAD_ACCESSORY_TARGET_UNRESOLVED'"))
                    .isEqualTo("0");
        }
        assertThat(query("SELECT count(*)::text FROM product_category_assignments "
                + "WHERE rule_version = 'customer-approved-2026-09-26-ipad-mac-cases-v1'"))
                .isEqualTo("13");
        assertThat(query("SELECT payroll.payroll_category_code "
                + "FROM product_payroll_category_assignments payroll "
                + "JOIN products product ON product.id = payroll.product_id "
                + "WHERE product.code = '6175'"))
                .isEqualTo("TECH_TIER_2");
        assertThat(query("SELECT count(*)::text "
                + "FROM product_payroll_category_assignments payroll "
                + "JOIN products product ON product.id = payroll.product_id "
                + "WHERE product.code IN ('6175', '2579', '3325', '3901', "
                + "'3784', '2591', '2972', '2973') "
                + "AND payroll.payroll_category_code = 'TECH_TIER_2'"))
                .isEqualTo("8");
        assertThat(query("SELECT count(*)::text FROM sales_document_items item "
                + "JOIN products product ON product.id = item.product_id "
                + "WHERE product.code IN ('6175', '2579', '3325', '3901', "
                + "'3784', '2591', '2972', '2973') "
                + "AND item.condition_type_snapshot = 'NOT_APPLICABLE'"))
                .isEqualTo("8");
        assertThat(query("SELECT sum(net_amount)::text || '|' || "
                + "sum(cost_amount)::text FROM sales_document_items"))
                .isEqualTo("1400.00|700.00");
        assertThat(query("SELECT string_agg(code || ':' || payroll_category_code, "
                + "',' ORDER BY code) FROM analytics_categories WHERE code IN "
                + "('ACCESSORY_IPAD', 'ACCESSORY_MAC', 'CASE_OTHER_DEVICE')"))
                .isEqualTo("ACCESSORY_IPAD:ACCESSORY,ACCESSORY_MAC:ACCESSORY,"
                        + "CASE_OTHER_DEVICE:ACCESSORY");
    }

    private void addFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (id, connection_id, source_system, external_id, name)
                    SELECT '00000000-0000-4000-8000-000000000651',
                           id, 'LIVESKLAD', 'split-test-store', 'Split test store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000652',
                           id, '00000000-0000-4000-8000-000000000651',
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
                        ('2470', 'Чехол KeePhone LENNO iPad 10/11'),
                        ('5844', 'Чехол для iPad Pro 13 Keephone Black Б/У'),
                        ('6175', 'Apple Pencil 1 2025'),
                        ('2579', 'Apple Pencil 2 New'),
                        ('3325', 'Apple Pencil (USB-C)'),
                        ('3901', 'Apple Pencil Pro NEW'),
                        ('3784', 'Magic Keyboard iPad Pro Black'),
                        ('2591', 'Apple Magic Mouse USB-C Black'),
                        ('2972', 'MAGIC MOUSE BLACK'),
                        ('2973', 'MAGIC MOUSE WHITE'),
                        ('4972', 'Чехол MacBook Keephone SMOKY MATTE'),
                        ('5051', 'Чехол Uniq для ноутбуков 14 серый'),
                        ('3628', 'Пленка для планшета'),
                        ('39', 'Чехол Keephone X-Crystal')
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
                    FROM (VALUES
                        ('2470', 'ACCESSORY_IPAD_MAC'),
                        ('2579', 'IPAD_MAC'),
                        ('3325', 'IPAD_MAC'),
                        ('3901', 'IPAD_MAC'),
                        ('3784', 'IPAD_MAC'),
                        ('2591', 'IPAD_MAC'),
                        ('2972', 'IPAD_MAC'),
                        ('2973', 'IPAD_MAC'),
                        ('4972', 'OTHER_ACCESSORY_PRODUCT'),
                        ('5051', 'OTHER_ACCESSORY_PRODUCT'),
                        ('3628', 'ACCESSORY_IPAD_MAC'),
                        ('39', 'OTHER_ACCESSORY_PRODUCT')
                    ) fixture(code, category_code)
                    JOIN products product ON product.code = fixture.code
                    JOIN analytics_categories category
                      ON category.code = fixture.category_code
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT '00000000-0000-4000-8000-000000000653',
                           id, 'LIVESKLAD', 'split-test-sale',
                           '00000000-0000-4000-8000-000000000651',
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', 1400, 700,
                           '00000000-0000-4000-8000-000000000652'
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
                    SELECT '00000000-0000-4000-8000-000000000653',
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'fixture-v1',
                           CASE WHEN category.code = 'IPAD_MAC' THEN 'NEW'
                                ELSE 'NOT_APPLICABLE' END,
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM (VALUES
                        ('2470', 'ACCESSORY_IPAD_MAC'),
                        ('5844', 'ACCESSORY_IPAD_MAC'),
                        ('6175', 'IPAD_MAC'),
                        ('2579', 'IPAD_MAC'),
                        ('3325', 'IPAD_MAC'),
                        ('3901', 'IPAD_MAC'),
                        ('3784', 'IPAD_MAC'),
                        ('2591', 'IPAD_MAC'),
                        ('2972', 'IPAD_MAC'),
                        ('2973', 'IPAD_MAC'),
                        ('4972', 'OTHER_ACCESSORY_PRODUCT'),
                        ('5051', 'OTHER_ACCESSORY_PRODUCT'),
                        ('3628', 'ACCESSORY_IPAD_MAC'),
                        ('39', 'OTHER_ACCESSORY_PRODUCT')
                    ) fixture(code, category_code)
                    JOIN products product ON product.code = fixture.code
                    JOIN analytics_categories category
                      ON category.code = fixture.category_code
                    """);
        }
    }

    private void assertCategory(String code, String expected) throws SQLException {
        for (String table : new String[]{
                "product_category_assignments", "sales_document_items"
        }) {
            assertThat(query("SELECT category.code FROM " + table + " item "
                    + "JOIN products product ON product.id = item.product_id "
                    + "JOIN analytics_categories category "
                    + "ON category.id = item.analytics_category_id "
                    + "WHERE product.code = '" + code + "'"))
                    .isEqualTo(expected);
        }
    }

    private String units(String view, String metric) throws SQLException {
        return query("SELECT coalesce(sum(net_quantity), 0)::integer::text "
                + "FROM " + view + " WHERE numerator_metric_code = '" + metric + "'");
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
