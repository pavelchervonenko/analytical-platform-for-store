package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
class ZeroCostSeverityMigrationIntegrationTest {

    private static final String STORE_ID =
            "00000000-0000-0000-0000-000000000541";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void changesOnlyOpenUnexpectedZeroCostIssuesAndIsIdempotent()
            throws Exception {
        flyway("53").migrate();
        insertFixtures();

        flyway("54").migrate();

        assertThat(severity("sale-zero-open")).isEqualTo("INFO");
        assertThat(severity("return-zero-open")).isEqualTo("INFO");
        assertThat(severity("sale-zero-resolved")).isEqualTo("WARNING");
        assertThat(severity("other-open")).isEqualTo("WARNING");
        assertThat(currentVersion()).isEqualTo("54");

        rerunMigrationSql();
        assertThat(severity("sale-zero-open")).isEqualTo("INFO");
        assertThat(severity("return-zero-open")).isEqualTo("INFO");
    }

    private void insertFixtures() throws SQLException {
        update("""
                INSERT INTO stores (id, source_system, external_id, name)
                VALUES (
                    '%s', 'MANUAL', 'zero-cost-store', 'Zero cost store'
                )
                """.formatted(STORE_ID));
        insertIssue(
                "sale-zero-open",
                "ZERO_UNEXPECTED_COST",
                "OPEN",
                "NULL"
        );
        insertIssue(
                "return-zero-open",
                "RETURN_ZERO_UNEXPECTED_COST",
                "OPEN",
                "NULL"
        );
        insertIssue(
                "sale-zero-resolved",
                "ZERO_UNEXPECTED_COST",
                "RESOLVED",
                "now()"
        );
        insertIssue(
                "other-open",
                "MISSING_COST",
                "OPEN",
                "NULL"
        );
    }

    private void insertIssue(
            String entityId,
            String issueCode,
            String status,
            String resolvedAt
    ) throws SQLException {
        update("""
                INSERT INTO data_quality_issues (
                    store_id, entity_type, entity_id, issue_code,
                    severity, status, message, resolved_at
                ) VALUES (
                    '%s', 'SALE_ITEM', '%s', '%s',
                    'WARNING', '%s', 'Fixture issue', %s
                )
                """.formatted(
                        STORE_ID,
                        entityId,
                        issueCode,
                        status,
                        resolvedAt
                ));
    }

    private String severity(String entityId) throws SQLException {
        return scalar("""
                SELECT severity
                FROM data_quality_issues
                WHERE entity_id = '%s'
                """.formatted(entityId));
    }

    private void rerunMigrationSql() throws IOException, SQLException {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V54__make_unexpected_zero_cost_informational.sql"
        )) {
            assertThat(input).isNotNull();
            update(new String(input.readAllBytes(), StandardCharsets.UTF_8));
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

    private void update(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String scalar(String sql) throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private String currentVersion() throws SQLException {
        return scalar("""
                SELECT version
                FROM flyway_schema_history
                WHERE success
                ORDER BY installed_rank DESC
                LIMIT 1
                """);
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}
