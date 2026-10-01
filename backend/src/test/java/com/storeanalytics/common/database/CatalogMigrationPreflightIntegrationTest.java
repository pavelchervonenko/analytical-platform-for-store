package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
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
