package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.metrics.cases.CaseAttachRepository;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class CaseAttachMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void keepsReceiptSuggestionOutOfOfficialRateAndRecalculatesConfirmedReturn() throws SQLException {
        flyway("69").migrate();
        addFixtures();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            var historical = HistoricalCatalogRows.snapshot(connection);
            flyway("72").migrate();
            assertThat(HistoricalCatalogRows.snapshot(connection)).isEqualTo(historical);
        }

        // Prospective migrations do not classify old receipts. Prepare explicit fixture
        // decisions separately before testing the confirmed-case attach projection.
        update("""
                UPDATE sales_document_items
                SET analytics_category_id = (SELECT id FROM analytics_categories WHERE code = 'OTHER_CASE')
                WHERE external_id IN ('case-mixed', 'case-iphone', 'case-return')
                """);

        assertThat(query("SELECT payroll_category_code FROM analytics_categories "
                + "WHERE code = 'OTHER_CASE' ")).isEqualTo("ACCESSORY");
        assertThat(query("SELECT count(*)::text FROM sales_document_items item "
                + "JOIN analytics_categories category ON category.id = item.analytics_category_id "
                + "WHERE category.code = 'OTHER_CASE' ")).isEqualTo("3");
        assertThat(query("SELECT proposed_target FROM case_attach_review_items "
                + "WHERE product_code = '39' ")).isEqualTo("CONFLICT");
        assertThat(query("SELECT proposed_target FROM case_attach_review_items "
                + "WHERE product_code = '36' ")).isEqualTo("IPHONE");
        for (String view : new String[]{"attach_rate_item_facts_v3_with_cases",
                "attach_rate_item_facts_v4_with_cases"}) {
            assertThat(units(view, "CASE_APPLE_IPHONE")).isEqualTo("0");
            assertThat(units(view, "CASE_SAMSUNG")).isEqualTo("0");
        }

        decide("CASE_APPLE_IPHONE", 1);
        for (String view : new String[]{"attach_rate_item_facts_v3_with_cases",
                "attach_rate_item_facts_v4_with_cases"}) {
            assertThat(units(view, "CASE_APPLE_IPHONE")).isEqualTo("1");
            assertThat(units(view, "CASE_SAMSUNG")).isEqualTo("0");
        }
        assertThat(query("SELECT count(*)::text FROM case_attach_review_items "
                + "WHERE product_code = '39' AND decision_current")).isEqualTo("1");
        update("UPDATE sales_document_items SET unit_price = 120 "
                + "WHERE external_id = 'case-mixed'");
        assertThat(query("SELECT decision_current::text FROM case_attach_review_items "
                + "WHERE product_code = '39' ")).isEqualTo("true");
        assertThat(units("attach_rate_item_facts_v4_with_cases", "CASE_APPLE_IPHONE"))
                .isEqualTo("1");
        assertThat(query("SELECT sum(net_amount)::text FROM sales_document_items"))
                .isEqualTo("600.00");
        var repository = new CaseAttachRepository(new NamedParameterJdbcTemplate(
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(), POSTGRES.getPassword())));
        UUID storeId = UUID.fromString("00000000-0000-4000-8000-000000000701");
        LocalDate returnDate = LocalDate.parse("2026-09-04");
        assertThat(repository.unresolvedReturnCount(storeId, returnDate, returnDate)).isZero();
        update("UPDATE sales_document_items SET original_item_id = NULL "
                + "WHERE external_id = 'case-return'");
        assertThat(repository.unresolvedReturnCount(storeId, returnDate, returnDate)).isEqualTo(1);
        assertThat(units("attach_rate_item_facts_v4_with_cases", "CASE_APPLE_IPHONE"))
                .isEqualTo("2");
        update("UPDATE sales_document_items SET original_item_id = "
                + "'00000000-0000-4000-8000-000000000706' "
                + "WHERE external_id = 'case-return'");

        update("UPDATE sales_document_items SET analytics_category_id = "
                + "(SELECT id FROM analytics_categories WHERE code = 'OTHER_ACCESSORY_PRODUCT') "
                + "WHERE external_id = 'case-return'");
        for (String view : new String[]{"attach_rate_item_facts_v3_with_cases",
                "attach_rate_item_facts_v4_with_cases"}) {
            assertThat(units(view, "CASE_APPLE_IPHONE")).isEqualTo("1");
        }

        update("UPDATE sales_document_items SET quantity = 3 WHERE external_id = 'case-mixed'");
        assertThat(units("attach_rate_item_facts_v3_with_cases", "CASE_APPLE_IPHONE"))
                .isEqualTo("0");
        assertThat(query("SELECT decision_current::text FROM case_attach_review_items "
                + "WHERE product_code = '39' ")).isEqualTo("false");

        decide("CASE_SAMSUNG", 2);
        for (String view : new String[]{"attach_rate_item_facts_v3_with_cases",
                "attach_rate_item_facts_v4_with_cases"}) {
            assertThat(units(view, "CASE_APPLE_IPHONE")).isEqualTo("0");
            assertThat(units(view, "CASE_SAMSUNG")).isEqualTo("2");
        }
        assertThat(query("SELECT count(*)::text FROM case_attach_decisions"))
                .isEqualTo("2");
        assertThatThrownBy(() -> update("UPDATE case_attach_decisions SET target_code = 'DEFER'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> update("DELETE FROM case_attach_decisions"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
        update("""
                INSERT INTO stores (id, connection_id, source_system, external_id, name)
                SELECT '00000000-0000-4000-8000-000000000712', id,
                       'LIVESKLAD', 'case-test-other-store', 'Other store'
                FROM integration_connections WHERE connection_key = 'livesklad-default'
                """);
        update("UPDATE sales_documents SET store_id = "
                + "'00000000-0000-4000-8000-000000000712' "
                + "WHERE external_id = 'case-sale-mixed'");
        assertThat(query("SELECT decision_current::text FROM case_attach_review_items "
                + "WHERE product_code = '39' ")).isEqualTo("false");
        assertThat(query("SELECT store_id::text FROM case_attach_review_items "
                + "WHERE product_code = '39' "))
                .isEqualTo("00000000-0000-4000-8000-000000000712");
        assertThat(units("attach_rate_item_facts_v4_with_cases", "CASE_SAMSUNG"))
                .isEqualTo("0");
        assertThat(query("SELECT COALESCE(decision_target_code, 'NONE') "
                + "FROM case_attach_review_items WHERE product_code = '39'"))
                .isEqualTo("NONE");
        assertThat(query("SELECT decision_revision::text FROM case_attach_review_items "
                + "WHERE product_code = '39'")).isEqualTo("2");
        UUID movedStoreId = UUID.fromString("00000000-0000-4000-8000-000000000712");
        UUID movedSourceId = UUID.fromString("00000000-0000-4000-8000-000000000706");
        assertThat(repository.history(movedStoreId, movedSourceId)).isEmpty();
        assertThat(repository.history(storeId, movedSourceId)).hasSize(2);
        decide("CASE_APPLE_IPHONE", 3);
        assertThat(repository.history(movedStoreId, movedSourceId)).hasSize(1);
        assertThat(units("attach_rate_item_facts_v4_with_cases", "CASE_APPLE_IPHONE"))
                .isEqualTo("3");
    }

    private void addFixtures() throws SQLException {
        update("""
                INSERT INTO stores (id, connection_id, source_system, external_id, name)
                SELECT '00000000-0000-4000-8000-000000000701', id,
                       'LIVESKLAD', 'case-test-store', 'Case test store'
                FROM integration_connections WHERE connection_key = 'livesklad-default'
                """);
        update("""
                INSERT INTO sync_runs (id, connection_id, store_id, source_system,
                    trigger_type, sync_scope, status, started_at, finished_at)
                SELECT '00000000-0000-4000-8000-000000000702', id,
                       '00000000-0000-4000-8000-000000000701', 'LIVESKLAD',
                       'MANUAL', 'SALES', 'SUCCESS', '2026-09-01T10:00:00Z',
                       '2026-09-01T10:01:00Z'
                FROM integration_connections WHERE connection_key = 'livesklad-default'
                """);
        update("""
                INSERT INTO products (connection_id, source_system, external_id,
                    code, name, source_kind)
                SELECT connection.id, 'LIVESKLAD', fixture.code, fixture.code,
                       fixture.name, 'PRODUCT'
                FROM integration_connections connection
                CROSS JOIN (VALUES
                    ('39', 'Чехол Keephone X-Crystal'),
                    ('36', 'Чехол прозрачный (открытая камера)'),
                    ('P1', 'iPhone 16 Pro'), ('P2', 'Samsung Galaxy S25')
                ) fixture(code, name)
                WHERE connection.connection_key = 'livesklad-default'
                """);
        update("""
                INSERT INTO product_category_assignments (product_id, analytics_category_id,
                    condition_type, assignment_source, rule_version, valid_from)
                SELECT product.id, category.id, 'NOT_APPLICABLE', 'AUTO',
                       'fixture-v1', '2026-09-01T00:00:00Z'
                FROM products product
                JOIN analytics_categories category ON category.code = 'OTHER_ACCESSORY_PRODUCT'
                WHERE product.code IN ('39', '36')
                """);
        update("""
                INSERT INTO sales_documents (id, connection_id, source_system, external_id,
                    store_id, original_document_id, document_number, document_kind,
                    source_document_type, occurred_at, business_date, net_amount,
                    cost_amount, last_sync_run_id)
                SELECT fixture.id::uuid, connection.id, 'LIVESKLAD', fixture.external_id,
                       '00000000-0000-4000-8000-000000000701', fixture.original_id::uuid,
                       fixture.number, fixture.kind, fixture.kind,
                       fixture.occurred_at::timestamptz, fixture.business_date::date,
                       fixture.amount, fixture.cost, '00000000-0000-4000-8000-000000000702'
                FROM integration_connections connection
                CROSS JOIN (VALUES
                    ('00000000-0000-4000-8000-000000000703', 'case-sale-mixed',
                     NULL, '100', 'SALE', '2026-09-02T10:00:00Z', '2026-09-02', 300, 150),
                    ('00000000-0000-4000-8000-000000000704', 'case-sale-iphone',
                     NULL, '101', 'SALE', '2026-09-03T10:00:00Z', '2026-09-03', 100, 50),
                    ('00000000-0000-4000-8000-000000000705', 'case-return',
                     '00000000-0000-4000-8000-000000000703', '102', 'RETURN',
                     '2026-09-04T10:00:00Z', '2026-09-04', 100, 50)
                ) fixture(id, external_id, original_id, number, kind, occurred_at,
                          business_date, amount, cost)
                WHERE connection.connection_key = 'livesklad-default'
                """);
        update("""
                INSERT INTO sales_document_items (id, sales_document_id, external_id,
                    original_item_id, product_id, product_name_snapshot,
                    analytics_category_id, classification_version,
                    condition_type_snapshot, quantity, unit_price, gross_amount,
                    discount_amount, net_amount, cost_amount, cost_quality, is_work)
                SELECT fixture.id::uuid, fixture.document_id::uuid, fixture.external_id,
                       fixture.original_id::uuid, product.id, product.name,
                       category.id, 'fixture-v1', fixture.condition_type,
                       fixture.quantity, 100, 100, 0, 100, 50, 'KNOWN', false
                FROM (VALUES
                    ('00000000-0000-4000-8000-000000000706',
                     '00000000-0000-4000-8000-000000000703', 'case-mixed', NULL,
                     '39', 'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE', 2),
                    ('00000000-0000-4000-8000-000000000707',
                     '00000000-0000-4000-8000-000000000703', 'iphone-mixed', NULL,
                     'P1', 'IPHONE_NEW_ASIS', 'NEW', 1),
                    ('00000000-0000-4000-8000-000000000708',
                     '00000000-0000-4000-8000-000000000703', 'samsung-mixed', NULL,
                     'P2', 'SAMSUNG_NEW', 'NEW', 1),
                    ('00000000-0000-4000-8000-000000000709',
                     '00000000-0000-4000-8000-000000000704', 'case-iphone', NULL,
                     '36', 'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE', 1),
                    ('00000000-0000-4000-8000-000000000710',
                     '00000000-0000-4000-8000-000000000704', 'iphone-only', NULL,
                     'P1', 'IPHONE_NEW_ASIS', 'NEW', 1),
                    ('00000000-0000-4000-8000-000000000711',
                     '00000000-0000-4000-8000-000000000705', 'case-return',
                     '00000000-0000-4000-8000-000000000706',
                     '39', 'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE', 1)
                ) fixture(id, document_id, external_id, original_id, code,
                          category_code, condition_type, quantity)
                JOIN products product ON product.code = fixture.code
                JOIN analytics_categories category ON category.code = fixture.category_code
                """);
    }

    private void decide(String target, int revision) throws SQLException {
        update("""
                INSERT INTO case_attach_decisions (source_item_id, store_id, target_code,
                    source_fingerprint, reason, revision)
                SELECT source_item_id, store_id, '%s', source_fingerprint,
                       'Checked packaging', %d
                FROM case_attach_review_items WHERE product_code = '39'
                """.formatted(target, revision));
    }

    private String units(String view, String metric) throws SQLException {
        return query("SELECT COALESCE(sum(net_quantity), 0)::integer::text FROM "
                + view + " WHERE numerator_metric_code = '" + metric + "'");
    }

    private String query(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void update(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }
}
