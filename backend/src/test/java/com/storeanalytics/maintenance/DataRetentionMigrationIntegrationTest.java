package com.storeanalytics.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class DataRetentionMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void migratesExistingAuditHistoryAndRestoresImmutability() throws SQLException {
        migrate("11");
        UUID financialId = UUID.randomUUID();
        UUID securityId = UUID.randomUUID();
        try (Connection connection = connection()) {
            insertAudit(
                    connection,
                    financialId,
                    "PAYROLL_PAID",
                    Instant.parse("2020-01-01T00:00:00Z")
            );
            insertAudit(
                    connection,
                    securityId,
                    "USER_CREATED",
                    Instant.parse("2020-01-01T00:00:00Z")
            );
        }

        migrate(null);

        try (Connection connection = connection()) {
            assertRetention(connection, financialId, "FINANCIAL", null);
            assertRetention(
                    connection,
                    securityId,
                    "SECURITY",
                    Instant.parse("2025-01-01T00:00:00Z")
            );
        }
        assertThatThrownBy(() -> {
            try (Connection connection = connection();
                    PreparedStatement statement = connection.prepareStatement(
                            "UPDATE audit_log SET action = 'USER_CHANGED' WHERE id = ?"
                    )) {
                statement.setObject(1, securityId);
                statement.executeUpdate();
            }
        })
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("audit log entries are immutable");
    }

    private void migrate(String target) {
        var configuration = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                );
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private void insertAudit(
            Connection connection,
            UUID id,
            String action,
            Instant createdAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO audit_log (id, action, entity_type, entity_id, created_at)
                VALUES (?, ?, 'TEST', ?, ?)
                """
        )) {
            statement.setObject(1, id);
            statement.setString(2, action);
            statement.setString(3, id.toString());
            statement.setTimestamp(4, Timestamp.from(createdAt));
            statement.executeUpdate();
        }
    }

    private void assertRetention(
            Connection connection,
            UUID id,
            String expectedClass,
            Instant expectedDeadline
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                SELECT retention_class, retain_until
                FROM audit_log
                WHERE id = ?
                """
        )) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("retention_class"))
                        .isEqualTo(expectedClass);
                Timestamp deadline = resultSet.getTimestamp("retain_until");
                if (expectedDeadline == null) {
                    assertThat(deadline).isNull();
                } else {
                    assertThat(deadline.toInstant()).isEqualTo(expectedDeadline);
                }
            }
        }
    }
}
