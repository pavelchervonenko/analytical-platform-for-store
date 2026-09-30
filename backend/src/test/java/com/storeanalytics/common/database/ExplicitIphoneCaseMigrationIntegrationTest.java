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
class ExplicitIphoneCaseMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void correctsModelNamedCasesButRespectsOtherDevicesBundlesAndManualDecisions()
            throws SQLException {
        flyway("68").migrate();
        addFixtures();

        flyway("69").migrate();

        assertThat(count("product_category_assignments", "CASE_APPLE_IPHONE"))
                .isEqualTo("8");
        assertThat(count("product_category_assignments", "OTHER_ACCESSORY_PRODUCT"))
                .isEqualTo("5");
        assertThat(count("product_category_assignments", "GLASS_IPHONE"))
                .isEqualTo("1");
        assertThat(count("sales_document_items", "CASE_APPLE_IPHONE"))
                .isEqualTo("8");
        assertThat(count("sales_document_items", "OTHER_ACCESSORY_PRODUCT"))
                .isEqualTo("6");
        assertThat(query("SELECT count(*)::text FROM sales_document_items "
                + "WHERE classification_version = 'customer-approved-2026-09-26-"
                + "explicit-iphone-cases-v1'"))
                .isEqualTo("7");
        for (String view : new String[]{
                "attach_rate_item_facts_v3", "attach_rate_ordinary_item_facts_v4"
        }) {
            assertThat(units(view, "CASE_APPLE_IPHONE")).isEqualTo("8");
        }
        assertThat(query("SELECT count(DISTINCT document.store_id)::text "
                + "FROM sales_document_items item "
                + "JOIN sales_documents document ON document.id = item.sales_document_id "
                + "JOIN analytics_categories category ON category.id = item.analytics_category_id "
                + "WHERE category.code = 'CASE_APPLE_IPHONE'"))
                .isEqualTo("2");
        assertThat(query("SELECT sum(net_amount)::text || '|' || "
                + "sum(cost_amount)::text FROM sales_document_items"))
                .isEqualTo("1400.00|700.00");
    }

    private void addFixtures() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (id, connection_id, source_system, external_id, name)
                    SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                           fixture.external_id, fixture.name
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000691',
                         'explicit-case-store-a', 'Explicit case test A'),
                        ('00000000-0000-4000-8000-000000000692',
                         'explicit-case-store-b', 'Explicit case test B')
                    ) fixture(id, external_id, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000693',
                           id, '00000000-0000-4000-8000-000000000691',
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
                           fixture.code, fixture.name, fixture.source_kind
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('3984', 'Чехол Uniq Magsafe 17 Pro Combat Active', 'PRODUCT'),
                        ('2921', 'Чехол Uniq Magsafe 17 Pro Max Combat Active', 'PRODUCT'),
                        ('3508', 'Чехол LUXO 13 Pro Magsafe синий', 'PRODUCT'),
                        ('4937', 'Чехол прозрачный 16e', 'UNKNOWN'),
                        ('4578', 'Чехол VLP Puro Case 17 pro max', 'UNKNOWN'),
                        ('6053', 'Чехол VLP Aster Pro Case с MagSafe 17 Pro', 'PRODUCT'),
                        ('5700', 'Чехол Airity iPhone 17 Pro Max', 'PRODUCT'),
                        ('7001', 'Чехол VLP iPhone 18 Pro', 'PRODUCT'),
                        ('2928', 'Комплект чехол+стекло 13 Mini', 'UNKNOWN'),
                        ('5053', 'Чехол Uniq для ноутбуков 14"', 'PRODUCT'),
                        ('3217', 'Чехол Keephone Samsung S25 Ultra', 'PRODUCT'),
                        ('4999', 'Чехол для Xiaomi 17 Pro', 'PRODUCT'),
                        ('6000', 'Чехол Uniq 17 Pro ручное решение', 'PRODUCT'),
                        ('7000', 'Чехол Uniq 17 Pro другая категория', 'PRODUCT')
                    ) fixture(code, name, source_kind)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from
                    )
                    SELECT product.id, category.id, 'NOT_APPLICABLE',
                           CASE WHEN product.code = '6000' THEN 'MANUAL' ELSE 'AUTO' END,
                           'fixture-v1', '2026-09-01T00:00:00Z'
                    FROM products product
                    JOIN analytics_categories category ON category.code =
                        CASE WHEN product.code = '7001' THEN 'CASE_APPLE_IPHONE'
                             WHEN product.code = '7000' THEN 'GLASS_IPHONE'
                             ELSE 'OTHER_ACCESSORY_PRODUCT' END
                    WHERE product.code NOT IN ('6053', '5700')
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                           fixture.external_id, fixture.store_id::uuid,
                           'SALE', 'SALE', '2026-09-02T10:00:00Z',
                           '2026-09-02', fixture.net_amount, fixture.cost_amount,
                           '00000000-0000-4000-8000-000000000693'
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000694',
                         'explicit-case-sale-a',
                         '00000000-0000-4000-8000-000000000691', 1200, 600),
                        ('00000000-0000-4000-8000-000000000695',
                         'explicit-case-sale-b',
                         '00000000-0000-4000-8000-000000000692', 200, 100)
                    ) fixture(id, external_id, store_id, net_amount, cost_amount)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        sales_document_id, external_id, product_id,
                        product_name_snapshot, analytics_category_id,
                        classification_version, condition_type_snapshot,
                        quantity, unit_price, gross_amount, discount_amount,
                        net_amount, cost_amount, cost_quality, is_work
                    )
                    SELECT CASE WHEN product.code IN ('6053', '7001')
                               THEN '00000000-0000-4000-8000-000000000695'::uuid
                               ELSE '00000000-0000-4000-8000-000000000694'::uuid END,
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'fixture-v1', 'NOT_APPLICABLE',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category ON category.code =
                        CASE WHEN product.code = '5700' THEN 'CASE_APPLE_IPHONE'
                             ELSE 'OTHER_ACCESSORY_PRODUCT' END
                    WHERE product.code IN (
                        '3984', '2921', '3508', '4937', '4578', '6053', '5700',
                        '7001', '2928', '5053', '3217', '4999', '6000', '7000'
                    )
                    """);
        }
    }

    private String count(String table, String category) throws SQLException {
        return query("SELECT count(*)::text FROM " + table + " item "
                + "JOIN analytics_categories category ON category.id = item.analytics_category_id "
                + "WHERE category.code = '" + category + "'");
    }

    private String units(String view, String category) throws SQLException {
        return query("SELECT coalesce(sum(net_quantity), 0)::integer::text FROM "
                + view + " WHERE numerator_metric_code = '" + category + "'");
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
