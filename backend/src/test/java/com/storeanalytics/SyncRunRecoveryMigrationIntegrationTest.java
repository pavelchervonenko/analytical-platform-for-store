package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SyncRunRecoveryMigrationIntegrationTest {

    private static final String TERMINAL_RUN_ID =
            "00000000-0000-0000-0000-000000000201";
    private static final String OLDER_ACTIVE_RUN_ID =
            "00000000-0000-0000-0000-000000000202";
    private static final String NEWEST_ACTIVE_RUN_ID =
            "00000000-0000-0000-0000-000000000203";

    @Test
    void v20RepairsOrphansAndEnforcesOneRunningAttemptPerJob()
            throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                "postgres:16-alpine"
        )) {
            postgres.start();
            migrate(postgres, "19");
            insertV19JobsAndRuns(postgres);

            migrate(postgres, null);

            assertRun(postgres, TERMINAL_RUN_ID, "FAILED", true);
            assertRun(postgres, OLDER_ACTIVE_RUN_ID, "FAILED", true);
            assertRun(postgres, NEWEST_ACTIVE_RUN_ID, "RUNNING", false);
            assertThatThrownBy(() -> insertAnotherRunningAttempt(postgres))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ux_sync_runs_one_running_per_job");
        }
    }

    private void migrate(PostgreSQLContainer postgres, String target) {
        var configuration = Flyway.configure()
                .dataSource(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                )
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void insertV19JobsAndRuns(PostgreSQLContainer postgres)
            throws SQLException {
        update(postgres, """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase,
                    period_start, period_end, cursor_start,
                    current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, started_at, finished_at
                )
                SELECT
                    '00000000-0000-0000-0000-000000000101', id,
                    'BACKFILL', 'FAILED', 'SALES',
                    '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z',
                    '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z',
                    1440, 5, '2026-01-01T00:00:00Z',
                    '2026-01-01T00:00:00Z', '2026-01-01T00:10:00Z'
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """);
        update(postgres, """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase,
                    period_start, period_end, cursor_start,
                    current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, lease_owner, lease_until, started_at
                )
                SELECT
                    '00000000-0000-0000-0000-000000000102', id,
                    'BACKFILL', 'RUNNING', 'SALES',
                    '2026-01-02T00:00:00Z', '2026-01-03T00:00:00Z',
                    '2026-01-02T00:00:00Z', '2026-01-03T00:00:00Z',
                    1440, 5, '2026-01-02T00:00:00Z',
                    'migration-test-worker', '2026-01-03T00:00:00Z',
                    '2026-01-02T00:00:00Z'
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """);
        update(postgres, """
                INSERT INTO sync_runs (
                    id, connection_id, source_system, trigger_type,
                    sync_scope, status, started_at, sync_job_id
                )
                SELECT
                    run_id, connection.id, 'LIVESKLAD', 'INITIAL',
                    'SALES', 'RUNNING', started_at, job_id
                FROM integration_connections connection
                CROSS JOIN (VALUES
                    ('00000000-0000-0000-0000-000000000201'::uuid,
                     '00000000-0000-0000-0000-000000000101'::uuid,
                     '2026-01-01T00:00:00Z'::timestamptz),
                    ('00000000-0000-0000-0000-000000000202'::uuid,
                     '00000000-0000-0000-0000-000000000102'::uuid,
                     '2026-01-02T00:00:00Z'::timestamptz),
                    ('00000000-0000-0000-0000-000000000203'::uuid,
                     '00000000-0000-0000-0000-000000000102'::uuid,
                     '2026-01-02T00:05:00Z'::timestamptz)
                ) AS seeded(run_id, job_id, started_at)
                WHERE connection.connection_key = 'livesklad-default'
                """);
    }

    private void assertRun(
            PostgreSQLContainer postgres,
            String runId,
            String expectedStatus,
            boolean expectedRecoveryError
    ) throws SQLException {
        try (Connection connection = connection(postgres);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("""
                        SELECT
                            run.status,
                            run.finished_at IS NOT NULL AS finished,
                            run.records_failed,
                            count(error.id) FILTER (
                                WHERE error.error_code =
                                    'SYNC_WORKER_LEASE_EXPIRED'
                            ) AS recovery_errors
                        FROM sync_runs run
                        LEFT JOIN sync_run_errors error
                            ON error.sync_run_id = run.id
                        WHERE run.id = '%s'
                        GROUP BY run.id
                        """.formatted(runId))) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString("status")).isEqualTo(expectedStatus);
            assertThat(result.getBoolean("finished"))
                    .isEqualTo(expectedRecoveryError);
            assertThat(result.getInt("records_failed"))
                    .isEqualTo(expectedRecoveryError ? 1 : 0);
            assertThat(result.getInt("recovery_errors"))
                    .isEqualTo(expectedRecoveryError ? 1 : 0);
        }
    }

    private void insertAnotherRunningAttempt(PostgreSQLContainer postgres)
            throws SQLException {
        update(postgres, """
                INSERT INTO sync_runs (
                    connection_id, source_system, trigger_type,
                    sync_scope, status, sync_job_id
                )
                SELECT
                    id, 'LIVESKLAD', 'INITIAL', 'SALES', 'RUNNING',
                    '00000000-0000-0000-0000-000000000102'
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """);
    }

    private int update(PostgreSQLContainer postgres, String sql)
            throws SQLException {
        try (Connection connection = connection(postgres);
                Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private Connection connection(PostgreSQLContainer postgres)
            throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
    }
}
