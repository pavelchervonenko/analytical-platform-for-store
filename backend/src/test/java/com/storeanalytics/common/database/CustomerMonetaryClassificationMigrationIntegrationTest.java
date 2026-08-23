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
class CustomerMonetaryClassificationMigrationIntegrationTest {

    private static final int CORRECTION_COUNT = 14;
    private static final int NORMALIZED_ITEM_COUNT = 15;

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void repairsAssignmentsAndNormalizedItemsForCompleteApprovedScope()
            throws SQLException {
        flyway("34").migrate();
        addClassificationFixtures();

        flyway(null).migrate();

        assertThat(currentVersion()).isEqualTo("44");
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     WITH expected (
                         external_id, target_category, target_condition
                     ) AS (VALUES
                         ('2579', 'IPAD_MAC', 'NEW'),
                         ('2591', 'IPAD_MAC', 'NEW'),
                         ('2972', 'IPAD_MAC', 'NEW'),
                         ('2973', 'IPAD_MAC', 'NEW'),
                         ('3325', 'IPAD_MAC', 'NEW'),
                         ('3784', 'IPAD_MAC', 'NEW'),
                         ('3901', 'IPAD_MAC', 'NEW'),
                         ('2716', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
                         ('4302', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
                         ('4305', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
                         ('4575', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
                         ('4660', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
                         ('4661', 'PODS_WATCH_OTHER_DEVICE', 'NEW'),
                         ('3527', 'CHARGER_CABLE', 'NOT_APPLICABLE')
                     )
                     SELECT
                         count(DISTINCT product.id) AS product_count,
                         count(*) AS item_count,
                         count(*) FILTER (WHERE
                             assignment_category.code = expected.target_category
                             AND item_category.code = expected.target_category
                             AND assignment.condition_type = expected.target_condition
                             AND item.condition_type_snapshot = expected.target_condition
                             AND assignment.rule_version =
                                 'customer-approved-2026-08-14-v3'
                             AND item.classification_version =
                                 'customer-approved-2026-08-14-v3'
                             AND item.version = 1
                         ) AS corrected_count
                     FROM expected
                     JOIN products product
                       ON product.external_id = expected.external_id
                     JOIN product_category_assignments assignment
                       ON assignment.product_id = product.id
                     JOIN analytics_categories assignment_category
                       ON assignment_category.id = assignment.analytics_category_id
                     JOIN sales_document_items item
                       ON item.product_id = product.id
                     JOIN analytics_categories item_category
                       ON item_category.id = item.analytics_category_id
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("product_count")).isEqualTo(CORRECTION_COUNT);
            assertThat(result.getInt("item_count")).isEqualTo(NORMALIZED_ITEM_COUNT);
            assertThat(result.getInt("corrected_count")).isEqualTo(NORMALIZED_ITEM_COUNT);
        }
        assertCorrectedFinancialGroupsWithoutChangingSourceAmounts();
        assertAdditionalRevenueFlagMatchesMonetaryKinds();
        assertPhoneFlagIsSubsetOfDeviceFlag();
        assertConfirmedAppleDevicesUseTierTwoPayrollDefault();
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

    private void addClassificationFixtures() throws SQLException {
        addStoreSyncAndProducts();
        addAssignmentsAndItems();
    }

    private void addStoreSyncAndProducts() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name
                    )
                    SELECT
                        '00000000-0000-4000-8000-000000000351',
                        id,
                        'LIVESKLAD',
                        'monetary-classification-migration-store',
                        'Monetary classification migration store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id, connection_id, store_id, source_system, trigger_type,
                        sync_scope, status, started_at, finished_at
                    )
                    SELECT
                        '00000000-0000-4000-8000-000000000352',
                        id,
                        '00000000-0000-4000-8000-000000000351',
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
                    WITH fixture (
                        external_id, product_name, old_category, old_condition
                    ) AS (VALUES
                        ('2579', 'Apple Pencil 2 New',
                            'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('2591', 'Apple Magic Mouse USB-C Black',
                            'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('2972', 'MAGIC MOUSE BLACK',
                            'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('2973', 'MAGIC MOUSE WHITE',
                            'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('3325', 'Apple Pencil (USB-C)',
                            'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('3784', 'Magic Keyboard iPad Pro Black',
                            'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('3901', 'Apple Pencil Pro NEW',
                            'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('2716', 'PlayStation 5 Dualsense White',
                            'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4302', 'PlayStation 5 Dualsense Camouflage',
                            'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4305', 'PlayStation 5 Dualsense Camouflage New',
                            'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4575', 'PlayStation 5 Dualsense Midnight Black',
                            'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4660', 'PlayStation 5 Dualsense Galactic Purple',
                            'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4661', 'PlayStation 5 Dualsense Sterling Silver',
                            'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('3527', 'iPhone Air Magsafe Battery Pack',
                            'IPAD_MAC', 'NEW')
                    )
                    INSERT INTO products (
                        connection_id, source_system, external_id, code, name,
                        source_kind
                    )
                    SELECT
                        connection.id,
                        'LIVESKLAD',
                        fixture.external_id,
                        fixture.external_id,
                        fixture.product_name,
                        'PRODUCT'
                    FROM integration_connections connection
                    CROSS JOIN fixture
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
        }
    }

    private void addAssignmentsAndItems() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    WITH fixture (external_id, old_category, old_condition) AS (VALUES
                        ('2579', 'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('2591', 'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('2972', 'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('2973', 'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('3325', 'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('3784', 'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('3901', 'ACCESSORY_IPAD_MAC', 'NOT_APPLICABLE'),
                        ('2716', 'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4302', 'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4305', 'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4575', 'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4660', 'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('4661', 'OTHER_ACCESSORY_PRODUCT', 'NOT_APPLICABLE'),
                        ('3527', 'IPAD_MAC', 'NEW')
                    )
                    INSERT INTO product_category_assignments (
                        product_id, analytics_category_id, condition_type,
                        assignment_source, rule_version, valid_from, change_reason
                    )
                    SELECT
                        product.id,
                        category.id,
                        fixture.old_condition,
                        'INITIAL_IMPORT',
                        'customer-approved-2026-08-07-v2',
                        '2025-12-31T22:00:00Z',
                        'Previous customer-approved classification'
                    FROM fixture
                    JOIN products product
                      ON product.external_id = fixture.external_id
                    JOIN analytics_categories category
                      ON category.code = fixture.old_category
                    """);
            statement.executeUpdate("""
                    INSERT INTO sales_documents (
                        id, connection_id, source_system, external_id, store_id,
                        document_kind, source_document_type, occurred_at,
                        business_date, net_amount, cost_amount, last_sync_run_id
                    )
                    SELECT
                        '00000000-0000-4000-8000-000000000353',
                        id,
                        'LIVESKLAD',
                        'monetary-classification-sale',
                        '00000000-0000-4000-8000-000000000351',
                        'SALE',
                        'SALE',
                        '2026-08-01T10:00:00Z',
                        '2026-08-01',
                        1400,
                        700,
                        '00000000-0000-4000-8000-000000000352'
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
                    SELECT
                        '00000000-0000-4000-8000-000000000353',
                        'item-' || product.external_id,
                        product.id,
                        product.name,
                        assignment.analytics_category_id,
                        assignment.id,
                        'customer-approved-2026-08-07-v2',
                        assignment.condition_type,
                        1,
                        100,
                        100,
                        0,
                        100,
                        50,
                        'KNOWN',
                        false
                    FROM products product
                    JOIN product_category_assignments assignment
                      ON assignment.product_id = product.id
                    WHERE product.external_id IN (
                        '2579', '2591', '2972', '2973', '3325', '3784', '3901',
                        '2716', '4302', '4305', '4575', '4660', '4661', '3527'
                    )
                    """);
            addReturnDocumentAndItem(statement);
        }
    }

    private void addReturnDocumentAndItem(Statement statement)
            throws SQLException {
        statement.executeUpdate("""
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id,
                    original_document_id, document_kind, source_document_type,
                    occurred_at, business_date, net_amount, cost_amount,
                    last_sync_run_id
                )
                SELECT
                    '00000000-0000-4000-8000-000000000354',
                    id,
                    'LIVESKLAD',
                    'monetary-classification-return',
                    '00000000-0000-4000-8000-000000000351',
                    '00000000-0000-4000-8000-000000000353',
                    'RETURN',
                    'RETURN',
                    '2026-08-02T10:00:00Z',
                    '2026-08-02',
                    25,
                    10,
                    '00000000-0000-4000-8000-000000000352'
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
                SELECT
                    '00000000-0000-4000-8000-000000000354',
                    'return-item-2579',
                    product.id,
                    product.name,
                    assignment.analytics_category_id,
                    assignment.id,
                    'customer-approved-2026-08-07-v2',
                    assignment.condition_type,
                    0.25,
                    100,
                    25,
                    0,
                    25,
                    10,
                    'KNOWN',
                    false
                FROM products product
                JOIN product_category_assignments assignment
                  ON assignment.product_id = product.id
                WHERE product.external_id = '2579'
                """);
    }

    private void assertCorrectedFinancialGroupsWithoutChangingSourceAmounts()
            throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT
                         sum(
                             CASE document.document_kind
                                 WHEN 'SALE' THEN 1 ELSE -1
                             END * item.net_amount
                         ) AS total_revenue,
                         sum(
                             CASE document.document_kind
                                 WHEN 'SALE' THEN 1 ELSE -1
                             END * item.cost_amount
                         ) AS total_cost,
                         sum(
                             CASE document.document_kind
                                 WHEN 'SALE' THEN 1 ELSE -1
                             END * item.net_amount
                         ) FILTER (
                             WHERE category.counts_as_additional_revenue
                         ) AS additional_revenue
                     FROM sales_document_items item
                     JOIN sales_documents document
                       ON document.id = item.sales_document_id
                     JOIN analytics_categories category
                       ON category.id = item.analytics_category_id
                     WHERE item.sales_document_id IN (
                         '00000000-0000-4000-8000-000000000353',
                         '00000000-0000-4000-8000-000000000354'
                     )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBigDecimal("total_revenue"))
                    .isEqualByComparingTo("1375.00");
            assertThat(result.getBigDecimal("total_cost"))
                    .isEqualByComparingTo("690.00");
            assertThat(result.getBigDecimal("additional_revenue"))
                    .isEqualByComparingTo("100.00");
        }
    }

    private void assertAdditionalRevenueFlagMatchesMonetaryKinds()
            throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT count(*) AS mismatch_count
                     FROM analytics_categories
                     WHERE is_active
                       AND counts_as_additional_revenue IS DISTINCT FROM (
                           category_kind IN (
                               'ACCESSORY', 'SERVICE', 'WARRANTY', 'PROTECTION'
                           )
                       )
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("mismatch_count")).isZero();
        }
    }

    private void assertPhoneFlagIsSubsetOfDeviceFlag()
            throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT count(*) AS mismatch_count
                     FROM analytics_categories
                     WHERE is_active
                       AND counts_as_phone
                       AND NOT counts_as_device
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("mismatch_count")).isZero();
        }
    }

    private void assertConfirmedAppleDevicesUseTierTwoPayrollDefault()
            throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT count(*) AS correctly_resolved_count
                     FROM products product
                     JOIN product_category_assignments assignment
                       ON assignment.product_id = product.id
                     JOIN analytics_categories category
                       ON category.id = assignment.analytics_category_id
                     WHERE product.external_id IN (
                         '2579', '2591', '2972', '2973',
                         '3325', '3784', '3901'
                     )
                       AND category.code = 'IPAD_MAC'
                       AND resolve_default_payroll_category(
                           category.code,
                           product.name,
                           category.payroll_category_code
                       ) = 'TECH_TIER_2'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("correctly_resolved_count")).isEqualTo(7);
        }
    }

    private String currentVersion() {
        return flyway(null).info().current().getVersion().getVersion();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}
