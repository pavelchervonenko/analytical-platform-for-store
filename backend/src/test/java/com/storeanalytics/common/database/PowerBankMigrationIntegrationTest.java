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
class PowerBankMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void separatesPowerBanksFromChargersInBothStoresAndBothAttachProjections()
            throws SQLException {
        flyway("74").migrate();
        addFixtures();

        flyway("75").migrate();

        assertThat(query("""
                SELECT category.category_kind || '|' || category.device_family || '|'
                       || category.counts_as_additional_revenue || '|'
                       || category.attach_denominator_code || '|'
                       || category.payroll_category_code
                FROM analytics_categories category WHERE category.code = 'POWER_BANK'
                """)).isEqualTo("ACCESSORY|NONE|true|PHONE|ACCESSORY");
        assertThat(query("""
                SELECT count(*)::text FROM attach_rate_metric_definitions_v3
                WHERE metric_code = 'POWER_BANK'
                  AND numerator_category_code = 'POWER_BANK'
                  AND denominator_code = 'PHONE'
                """)).isEqualTo("1");
        assertThat(query("""
                SELECT count(*)::text
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                JOIN analytics_categories category ON category.id = assignment.analytics_category_id
                WHERE category.code = 'POWER_BANK'
                  AND product.code IN ('3527', '69', '32532', '4543', '1936')
                  AND assignment.condition_type = 'NOT_APPLICABLE'
                """)).isEqualTo("5");
        assertThat(query("""
                SELECT count(*)::text
                FROM sales_document_items item
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE category.code = 'POWER_BANK'
                  AND item.classification_version = 'customer-approved-2026-09-27-power-bank-v1'
                """)).isEqualTo("6");
        assertThat(query("""
                SELECT category.code FROM sales_document_items item
                JOIN products product ON product.id = item.product_id
                JOIN analytics_categories category ON category.id = item.analytics_category_id
                WHERE product.code = '9000'
                """)).isEqualTo("CHARGER_CABLE");
        assertThat(query("""
                SELECT sum(net_amount)::text || '|' || sum(cost_amount)::text
                FROM sales_document_items
                """)).isEqualTo("800.00|400.00");
        assertThat(query("SELECT count(*)::text FROM product_payroll_category_assignments"))
                .isEqualTo("0");

        for (String view : new String[]{
                "attach_rate_item_facts_v3", "attach_rate_ordinary_item_facts_v4"
        }) {
            assertThat(query("SELECT sum(net_quantity)::text FROM " + view
                    + " WHERE numerator_metric_code = 'POWER_BANK'"))
                    .as(view + " power-bank net units").isEqualTo("4.000");
            assertThat(query("SELECT sum(net_quantity)::text FROM " + view
                    + " WHERE numerator_metric_code = 'CHARGER_CABLE'"))
                    .as(view + " charger net units").isEqualTo("1.000");
            assertThat(query("SELECT count(*)::text FROM " + view
                    + " WHERE device_role = 'IPHONE_NEW_ASIS'"
                    + " AND 'POWER_BANK' = ANY(denominator_metric_codes)"))
                    .as(view + " phone denominator").isEqualTo("1");
        }
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
                        ('00000000-0000-4000-8000-000000000751', 'power-store-a', 'A'),
                        ('00000000-0000-4000-8000-000000000752', 'power-store-b', 'B')
                    ) fixture(id, external_id, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000753',
                           id, '00000000-0000-4000-8000-000000000751',
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
                        ('3527', 'iPhone Air Magsafe Battery Pack'),
                        ('69', 'Повербанк Magsafe Hoco 10k Mah J117A'),
                        ('32532', 'Powerbank HOCO Q 34 10K MAH'),
                        ('4543', 'Внешний аккумулятор VLP Solid Energy 5000mAh Qi2 20w белый'),
                        ('1936', 'Портативный аккумулятор Borofone BJ25 Plus PD20W 10K MAH'),
                        ('9000', 'Apple Power Adapter 30W Original'),
                        ('9001', 'iPhone 17 Pro 256GB NEW')
                    ) fixture(code, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from
                    )
                    SELECT product.id, category.id, 'NEW', 'AUTO',
                           'fixture-v1', '2026-09-01T00:00:00Z'
                    FROM products product
                    JOIN analytics_categories category ON category.code =
                        CASE WHEN product.code = '3527' THEN 'IPAD_MAC'
                             WHEN product.code = '9001' THEN 'IPHONE_NEW_ASIS'
                             ELSE 'CHARGER_CABLE' END
                    WHERE product.code <> '69'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                           fixture.external_id, fixture.store_id::uuid,
                           fixture.kind, fixture.kind, fixture.occurred_at::timestamptz,
                           fixture.business_date::date, fixture.net_amount,
                           fixture.cost_amount, '00000000-0000-4000-8000-000000000753'
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000754', 'power-sale-a',
                         '00000000-0000-4000-8000-000000000751',
                         'SALE', '2026-09-02T10:00:00Z', '2026-09-02', 400, 200),
                        ('00000000-0000-4000-8000-000000000755', 'power-sale-b',
                         '00000000-0000-4000-8000-000000000752',
                         'SALE', '2026-09-02T11:00:00Z', '2026-09-02', 300, 150),
                        ('00000000-0000-4000-8000-000000000756', 'power-return-a',
                         '00000000-0000-4000-8000-000000000751',
                         'RETURN', '2026-09-03T10:00:00Z', '2026-09-03', 100, 50)
                    ) fixture(id, external_id, store_id, kind, occurred_at,
                              business_date, net_amount, cost_amount)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        sales_document_id, external_id, product_id, product_name_snapshot,
                        analytics_category_id, classification_version, condition_type_snapshot,
                        quantity, unit_price, gross_amount, discount_amount,
                        net_amount, cost_amount, cost_quality, is_work
                    )
                    SELECT CASE WHEN product.code IN ('3527', '69', '9000', '9001')
                                THEN '00000000-0000-4000-8000-000000000754'::uuid
                                ELSE '00000000-0000-4000-8000-000000000755'::uuid END,
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'fixture-v1', 'NEW',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category ON category.code =
                        CASE WHEN product.code = '3527' THEN 'IPAD_MAC'
                             WHEN product.code = '9001' THEN 'IPHONE_NEW_ASIS'
                             ELSE 'CHARGER_CABLE' END
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_document_items (
                        sales_document_id, external_id, product_id, product_name_snapshot,
                        analytics_category_id, classification_version, condition_type_snapshot,
                        quantity, unit_price, gross_amount, discount_amount,
                        net_amount, cost_amount, cost_quality, is_work
                    )
                    SELECT '00000000-0000-4000-8000-000000000756',
                           'return-69', sale.product_id, sale.product_name_snapshot,
                           sale.analytics_category_id, 'fixture-v1', 'NEW',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM sales_document_items sale
                    WHERE sale.external_id = 'sale-69'
                    """);
        }
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
