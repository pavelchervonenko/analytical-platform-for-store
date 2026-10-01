package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class WorkShiftStoreScopeMigrationIntegrationTest {

    private static final String FIRST_STORE_ID =
            "00000000-0000-0000-0000-000000000521";
    private static final String SECOND_STORE_ID =
            "00000000-0000-0000-0000-000000000522";
    private static final String EMPLOYEE_ID =
            "00000000-0000-0000-0000-000000000523";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void upgradesPopulatedV51AndScopesShiftUniquenessToStore() throws SQLException {
        flyway("51").migrate();
        insertV51Fixture();

        flyway(null).migrate();

        assertThat(currentVersion()).isEqualTo("91");
        update("""
                INSERT INTO employee_work_shifts (
                    store_id, employee_id, work_date, worked_hours
                ) VALUES (
                    '%s', '%s', DATE '2026-09-19', 7.50
                )
                """.formatted(SECOND_STORE_ID, EMPLOYEE_ID));
        assertThat(shiftCount()).isEqualTo(2);
        assertThatThrownBy(() -> update("""
                INSERT INTO employee_work_shifts (
                    store_id, employee_id, work_date, worked_hours
                ) VALUES (
                    '%s', '%s', DATE '2026-09-19', 6.00
                )
                """.formatted(FIRST_STORE_ID, EMPLOYEE_ID)))
                .isInstanceOf(SQLException.class);
    }

    private void insertV51Fixture() throws SQLException {
        update("""
                INSERT INTO stores (id, source_system, external_id, name)
                VALUES
                    ('%s', 'MANUAL', 'migration-store-one', 'Migration store one'),
                    ('%s', 'MANUAL', 'migration-store-two', 'Migration store two')
                """.formatted(FIRST_STORE_ID, SECOND_STORE_ID));
        update("""
                INSERT INTO employees (
                    id, source_system, external_id, full_name
                ) VALUES (
                    '%s', 'MANUAL', 'migration-shared-employee', 'Shared employee'
                )
                """.formatted(EMPLOYEE_ID));
        update("""
                INSERT INTO employee_store_assignments (
                    employee_id, store_id, participates_in_ranking
                ) VALUES
                    ('%s', '%s', true),
                    ('%s', '%s', true)
                """.formatted(
                        EMPLOYEE_ID,
                        FIRST_STORE_ID,
                        EMPLOYEE_ID,
                        SECOND_STORE_ID
                ));
        update("""
                INSERT INTO employee_work_shifts (
                    store_id, employee_id, work_date, worked_hours
                ) VALUES (
                    '%s', '%s', DATE '2026-09-19', 11.00
                )
                """.formatted(FIRST_STORE_ID, EMPLOYEE_ID));
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

    private int shiftCount() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT count(*) FROM employee_work_shifts"
             )) {
            assertThat(result.next()).isTrue();
            return result.getInt(1);
        }
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
            return result.getString(1);
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
