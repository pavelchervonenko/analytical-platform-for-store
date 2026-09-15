package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class UserFeatureAccessMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void backfillsEveryFeatureForManagersOnlyAndConstrainsFutureValues() throws SQLException {
        flyway("48").migrate();
        addUsers();

        flyway(null).migrate();

        assertThat(currentVersion()).isEqualTo("51");
        assertThat(featuresFor("manager@example.com"))
                .containsExactly("PAYROLL", "PLAN", "SHIFTS");
        assertThat(featuresFor("admin@example.com")).isEmpty();
        assertThatThrownBy(this::insertUnsupportedFeature)
                .isInstanceOf(SQLException.class);
    }

    private void addUsers() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO app_users (
                        email, password_hash, display_name, role
                    ) VALUES
                        ('manager@example.com', 'hash', 'Manager', 'MANAGER'),
                        ('admin@example.com', 'hash', 'Administrator', 'ADMIN')
                    """);
        }
    }

    private List<String> featuresFor(String email) throws SQLException {
        List<String> features = new ArrayList<>();
        try (Connection connection = connection();
             var statement = connection.prepareStatement("""
                     SELECT access.feature
                     FROM user_feature_access access
                     JOIN app_users app_user ON app_user.id = access.user_id
                     WHERE app_user.email = ?
                     ORDER BY access.feature
                     """)) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    features.add(result.getString("feature"));
                }
            }
        }
        return List.copyOf(features);
    }

    private void insertUnsupportedFeature() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO user_feature_access (user_id, feature)
                    SELECT id, 'REPORTS'
                    FROM app_users
                    WHERE email = 'manager@example.com'
                    """);
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

    private String currentVersion() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT version
                     FROM flyway_schema_history
                     WHERE success
                     ORDER BY installed_rank DESC
                     LIMIT 1
                     """)) {
            assertThat(result.next()).isTrue();
            return result.getString("version");
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }
}
