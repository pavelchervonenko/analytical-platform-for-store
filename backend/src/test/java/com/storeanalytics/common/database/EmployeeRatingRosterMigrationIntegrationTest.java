package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class EmployeeRatingRosterMigrationIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void keepsOnlyCustomerApprovedPilotEmployeesInRanking() throws SQLException {
        flyway("40").migrate();
        addRosterFixture();

        flyway(null).migrate();

        assertThat(currentVersion()).isEqualTo("42");
        assertThat(rankingParticipation()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "\u041c\u043e\u0431\u0438\u0421\u0444\u0435\u0440\u0430/\u0410\u0440\u0442\u0443\u0440", true,
                "\u041c\u043e\u0431\u0438\u0421\u0444\u0435\u0440\u0430/\u041f\u043e\u0441\u0442\u043e\u0440\u043e\u043d\u043d\u0438\u0439", false,
                "\u041c\u0410\u0413\u0410\u0417\u0418\u041d/\u0412\u043e\u043b\u044c\u0444\u0431\u0435\u0440\u0433 \u0410\u043d\u0434\u0440\u0435\u0439", true,
                "\u041c\u0410\u0413\u0410\u0417\u0418\u041d/\u0414\u0440\u0443\u0433\u043e\u0439", false,
                "Other store/Outside pilot", true
        ));
    }

    private void addRosterFixture() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name
                    )
                    SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                           fixture.external_id, fixture.name
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000411',
                         'roster-store-mobi', '\u041c\u043e\u0431\u0438\u0421\u0444\u0435\u0440\u0430'),
                        ('00000000-0000-4000-8000-000000000412',
                         'roster-store-shop', '\u041c\u0410\u0413\u0410\u0417\u0418\u041d'),
                        ('00000000-0000-4000-8000-000000000413',
                         'roster-store-other', 'Other store')
                    ) fixture(id, external_id, name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO employees (
                        id, connection_id, source_system, external_id, full_name
                    )
                    SELECT fixture.id::uuid, connection.id, 'LIVESKLAD',
                           fixture.external_id, fixture.full_name
                    FROM integration_connections connection
                    CROSS JOIN (VALUES
                        ('00000000-0000-4000-8000-000000000421',
                         'roster-employee-artur', '\u0410\u0440\u0442\u0443\u0440'),
                        ('00000000-0000-4000-8000-000000000422',
                         'roster-employee-outsider', '\u041f\u043e\u0441\u0442\u043e\u0440\u043e\u043d\u043d\u0438\u0439'),
                        ('00000000-0000-4000-8000-000000000423',
                         'roster-employee-wolf', '\u0412\u043e\u043b\u044c\u0444\u0431\u0435\u0440\u0433 \u0410\u043d\u0434\u0440\u0435\u0439'),
                        ('00000000-0000-4000-8000-000000000424',
                         'roster-employee-other', '\u0414\u0440\u0443\u0433\u043e\u0439'),
                        ('00000000-0000-4000-8000-000000000425',
                         'roster-employee-outside-pilot', 'Outside pilot')
                    ) fixture(id, external_id, full_name)
                    WHERE connection.connection_key = 'livesklad-default'
                    """);
            statement.executeUpdate("""
                    INSERT INTO employee_store_assignments (
                        employee_id, store_id, participates_in_ranking
                    ) VALUES
                        ('00000000-0000-4000-8000-000000000421',
                         '00000000-0000-4000-8000-000000000411', true),
                        ('00000000-0000-4000-8000-000000000422',
                         '00000000-0000-4000-8000-000000000411', true),
                        ('00000000-0000-4000-8000-000000000423',
                         '00000000-0000-4000-8000-000000000412', false),
                        ('00000000-0000-4000-8000-000000000424',
                         '00000000-0000-4000-8000-000000000412', true),
                        ('00000000-0000-4000-8000-000000000425',
                         '00000000-0000-4000-8000-000000000413', true)
                    """);
        }
    }

    private Map<String, Boolean> rankingParticipation() throws SQLException {
        Map<String, Boolean> result = new HashMap<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT store.name, employee.full_name,
                            assignment.participates_in_ranking
                     FROM employee_store_assignments assignment
                     JOIN stores store ON store.id = assignment.store_id
                     JOIN employees employee ON employee.id = assignment.employee_id
                     WHERE employee.external_id LIKE 'roster-employee-%'
                     ORDER BY store.name, employee.full_name
                     """)) {
            while (rows.next()) {
                result.put(
                        rows.getString("name") + "/" + rows.getString("full_name"),
                        rows.getBoolean("participates_in_ranking")
                );
            }
        }
        return result;
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
