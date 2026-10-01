package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class CatalogMigrationPreflightIntegrationTest {
    @Test
    void checksConfiguredSchemaAndDeletedFactsWithoutReadingBusinessFields() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA audit_target");
                // Minimal fixture tests schema resolution and presence checks, not a migration restore.
                statement.execute("CREATE TABLE audit_target.sales_document_items(is_deleted boolean)");
                statement.execute("INSERT INTO audit_target.sales_document_items VALUES(true)");
            }
            Flyway target = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").defaultSchema("audit_target").load();
            assertThatThrownBy(() -> CatalogMigrationPreflight.verify(target))
                    .hasMessageContaining("CATALOG_PROSPECTIVE_ROLLOUT_REQUIRED");
            Flyway empty = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").defaultSchema("public").load();
            assertThatCode(() -> CatalogMigrationPreflight.verify(empty)).doesNotThrowAnyException();
        }
    }

    @Test
    void reviewedRolloutPinsSqlAndDetectsChangedHistoricalRows() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").target("51").load().migrate();
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO products(id, connection_id, external_id, name)
                        SELECT '00000000-0000-0000-0000-000000000195', id, 'guard-fixture', 'Guard fixture'
                        FROM integration_connections WHERE connection_key = 'livesklad-default'
                        """);
                statement.executeUpdate("""
                        INSERT INTO product_category_assignments(
                            product_id, analytics_category_id, assignment_source, valid_from)
                        SELECT '00000000-0000-0000-0000-000000000195', id, 'MANUAL', '2026-01-01Z'
                        FROM analytics_categories WHERE code = 'UNMAPPED'
                        """);
            }
            Flyway full = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load();
            assertThatThrownBy(() -> CatalogMigrationPreflight.prepareReviewedRollout(full, null))
                    .hasMessageContaining("CATALOG_PROSPECTIVE_ROLLOUT_REQUIRED");
            assertThatThrownBy(() -> CatalogMigrationPreflight.prepareReviewedRollout(full,
                    Instant.parse("2020-01-01T22:00:00Z")))
                    .hasMessageContaining("CATALOG_PROSPECTIVE_ROLLOUT_REQUIRED");
            assertThatThrownBy(() -> CatalogMigrationPreflight.prepareReviewedRollout(full,
                    Instant.parse("2099-01-01T12:00:00Z")))
                    .hasMessageContaining("business-day midnight");
            var fingerprint = CatalogMigrationPreflight.prepareReviewedRollout(
                    full, LocalDate.now(ZoneId.of("Europe/Kaliningrad")).plusDays(2)
                            .atStartOfDay(ZoneId.of("Europe/Kaliningrad")).toInstant());
            full.migrate();
            assertThatCode(() -> CatalogMigrationPreflight.verifyUnchanged(full, fingerprint))
                    .doesNotThrowAnyException();
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE product_category_assignments SET change_reason = 'synthetic drift'
                        WHERE product_id = '00000000-0000-0000-0000-000000000195'
                        """);
            }
            assertThatThrownBy(() -> CatalogMigrationPreflight.verifyUnchanged(full, fingerprint))
                    .hasMessageContaining("CATALOG_HISTORICAL_ROWS_CHANGED");
        }
    }

    @Test
    void allowsPopulatedDatabaseWhenHistoricalRewritesAreAlreadyApplied() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            Flyway flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration").load();
            CatalogMigrationPreflight.verify(flyway);
            flyway.migrate();
            try (var connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 var statement = connection.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO products(id, connection_id, external_id, name)
                        SELECT '00000000-0000-0000-0000-000000000194', id, 'synthetic', 'Synthetic'
                        FROM integration_connections WHERE connection_key = 'livesklad-default'
                        """);
                statement.executeUpdate("""
                        INSERT INTO product_category_assignments(
                            product_id, analytics_category_id, assignment_source, valid_from)
                        SELECT '00000000-0000-0000-0000-000000000194', id, 'MANUAL', '2026-01-01Z'
                        FROM analytics_categories WHERE code = 'UNMAPPED'
                        """);
            }
            assertThatCode(() -> CatalogMigrationPreflight.verify(flyway)).doesNotThrowAnyException();
        }
    }
}
