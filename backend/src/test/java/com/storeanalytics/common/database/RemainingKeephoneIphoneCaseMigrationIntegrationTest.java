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
class RemainingKeephoneIphoneCaseMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void preservesHistoricalRowsDuringProspectiveSchemaPreparation() throws SQLException {
        flyway("67").migrate();
        addFixtures();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var before = HistoricalCatalogRows.snapshot(connection);
            flyway("68").migrate();
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
                    SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                           fixture.external_id, fixture.name
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000681',
                         'keephone-test-store-a', 'Keephone test store A'),
                        ('00000000-0000-4000-8000-000000000682',
                         'keephone-test-store-b', 'Keephone test store B')
                    ) fixture(id, external_id, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system,
                        trigger_type, sync_scope, status, started_at, finished_at
                    )
                    SELECT '00000000-0000-4000-8000-000000000683',
                           id, '00000000-0000-4000-8000-000000000681',
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
                        ('4864', 'Чехол Keephone Armor Grip Magsafe 17 Pro Max Blue', 'UNKNOWN'),
                        ('4867', 'Чехол Keephone Glaze 17 Pro Max Clear', 'UNKNOWN'),
                        ('2862', 'Чехол Keephone Hybrid Pro 16 Clear', 'PRODUCT'),
                        ('2860', 'Чехол Keephone Hybrid Pro 16 Pro Clear', 'PRODUCT'),
                        ('2620', 'Чехол Keephone Hybrid Pro 17 Clear', 'PRODUCT'),
                        ('3425', 'Чехол Keephone Magviar Magsafe 15 Pro Max черный', 'PRODUCT'),
                        ('3309', 'Чехол Keephone Rosana silicone Magsafe 15 Pro Black', 'PRODUCT'),
                        ('4896', 'Чехол Keephone Rosana silicone Magsafe 15 Pro Clay', 'UNKNOWN'),
                        ('4897', 'Чехол Keephone Rosana silicone Magsafe 15 Pro Max Blue', 'PRODUCT'),
                        ('4898', 'Чехол Keephone Rosana silicone Magsafe 15 Pro Max Clay', 'PRODUCT'),
                        ('3311', 'Чехол Keephone Rosana silicone Magsafe 15 Pro серый', 'PRODUCT'),
                        ('3310', 'Чехол Keephone Rosana silicone Magsafe 15 Pro синий', 'PRODUCT'),
                        ('5882', 'Чехол Keephone Kevlar Sunset MagSafe Case iPhone 17', 'PRODUCT'),
                        ('38', 'Чехол Keephone Mago Pro Matte Magsafe', 'PRODUCT'),
                        ('3217', 'Чехол Keephone X-Crystal Samsung S25 Ultra', 'PRODUCT')
                    ) fixture(code, name, source_kind)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from
                    )
                    SELECT product.id, category.id, 'NOT_APPLICABLE',
                           'AUTO', 'fixture-v1', '2026-09-01T00:00:00Z'
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = 'OTHER_ACCESSORY_PRODUCT'
                    WHERE product.code <> '5882'
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
                           '00000000-0000-4000-8000-000000000683'
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000684',
                         'keephone-test-sale-a',
                         '00000000-0000-4000-8000-000000000681', 900, 450),
                        ('00000000-0000-4000-8000-000000000685',
                         'keephone-test-sale-b',
                         '00000000-0000-4000-8000-000000000682', 200, 100)
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
                    SELECT CASE WHEN product.code IN ('4897', '4898')
                               THEN '00000000-0000-4000-8000-000000000685'::uuid
                               ELSE '00000000-0000-4000-8000-000000000684'::uuid END,
                           'sale-' || product.code, product.id, product.name,
                           category.id, 'fixture-v1', 'NOT_APPLICABLE',
                           1, 100, 100, 0, 100, 50, 'KNOWN', false
                    FROM products product
                    JOIN analytics_categories category
                      ON category.code = CASE WHEN product.code = '5882'
                                              THEN 'CASE_APPLE_IPHONE'
                                              ELSE 'OTHER_ACCESSORY_PRODUCT' END
                    WHERE product.code IN (
                        '2862', '2860', '2620', '3425', '3309',
                        '4897', '4898', '3311', '3310', '5882', '38'
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
