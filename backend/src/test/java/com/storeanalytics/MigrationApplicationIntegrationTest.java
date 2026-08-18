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
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MigrationApplicationIntegrationTest {

    private static final String LEGACY_RAW_ID =
            "00000000-0000-0000-0000-000000000181";
    private static final String ROLLBACK_RAW_ID =
            "00000000-0000-0000-0000-000000000182";
    private static final String REPORT_STORE_ID =
            "00000000-0000-0000-0000-000000000191";
    private static final String REPORT_ID =
            "00000000-0000-0000-0000-000000000192";

    @Test
    void oneShotModeMigratesEmptyAndPreviousSchema() throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                "postgres:16-alpine"
        )) {
            postgres.start();

            runMigration(postgres);
            assertThat(currentVersion(postgres)).isEqualTo("41");

            resetSchema(postgres);
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword()
                    )
                    .locations("classpath:db/migration")
                    .target("29")
                    .load()
                    .migrate();
            assertThat(currentVersion(postgres)).isEqualTo("29");
            addVersion29Report(postgres);

            runMigration(postgres);
            assertThat(currentVersion(postgres)).isEqualTo("41");
            assertReportPayloadMigrated(postgres);
            assertFinalizedReportRemainsImmutable(postgres);

            resetSchema(postgres);
            Flyway.configure()
                    .dataSource(
                            postgres.getJdbcUrl(),
                            postgres.getUsername(),
                            postgres.getPassword()
                    )
                    .locations("classpath:db/migration")
                    .target("17")
                    .load()
                    .migrate();
            assertThat(currentVersion(postgres)).isEqualTo("17");
            addPreviousVersionRawWrite(postgres, LEGACY_RAW_ID, "legacy-before-v18");

            runMigration(postgres);
            assertThat(currentVersion(postgres)).isEqualTo("41");
            assertThat(payloadPolicyVersion(postgres, LEGACY_RAW_ID)).isZero();

            addPreviousVersionRawWrite(postgres, ROLLBACK_RAW_ID, "rollback-after-v18");
            assertThat(payloadPolicyVersion(postgres, ROLLBACK_RAW_ID)).isZero();
        }
    }

    private void runMigration(PostgreSQLContainer postgres) {
        String[] arguments = {
                "--app.runtime.role=MIGRATION",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--spring.flyway.locations=classpath:db/migration"
        };
        try (ConfigurableApplicationContext context =
                     MigrationApplication.run(arguments)) {
            assertThat(context.containsBean("flyway")).isTrue();
            assertThat(context.containsBean("entityManagerFactory")).isFalse();
            assertThat(context.containsBean("dispatcherServlet")).isFalse();
            Flyway flyway = context.getBean(Flyway.class);
            assertThat(flyway.getConfiguration().getInitSql())
                    .contains(
                            "lock_timeout",
                            "5000ms",
                            "statement_timeout",
                            "600000ms"
                    );
            assertThat(flyway.getConfiguration().getLockRetryCount())
                    .isEqualTo(10);
        }
    }

    private void addPreviousVersionRawWrite(
            PostgreSQLContainer postgres,
            String rawId,
            String externalId
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name
                    )
                    SELECT
                        '00000000-0000-0000-0000-000000000171',
                        id,
                        'LIVESKLAD',
                        'migration-compatibility-store',
                        'Migration compatibility store'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO sync_runs (
                        id,
                        connection_id,
                        store_id,
                        source_system,
                        trigger_type,
                        sync_scope,
                        status,
                        started_at,
                        finished_at
                    )
                    SELECT
                        '00000000-0000-0000-0000-000000000172',
                        connection.id,
                        '00000000-0000-0000-0000-000000000171',
                        'LIVESKLAD',
                        'SCHEDULED',
                        'STORES',
                        'SUCCESS',
                        '2026-07-27T00:00:00Z',
                        '2026-07-27T00:01:00Z'
                    FROM integration_connections connection
                    WHERE connection.connection_key = 'livesklad-default'
                    ON CONFLICT (id) DO NOTHING
                    """);
            statement.executeUpdate("""
                    INSERT INTO raw_record_versions (
                        id,
                        connection_id,
                        store_id,
                        source_system,
                        entity_type,
                        external_id,
                        payload,
                        payload_hash,
                        first_seen_at,
                        last_seen_at,
                        first_sync_run_id,
                        last_sync_run_id,
                        normalization_status,
                        normalized_at
                    )
                    SELECT
                        '%s',
                        connection.id,
                        '00000000-0000-0000-0000-000000000171',
                        'LIVESKLAD',
                        'STORE',
                        '%s',
                        '{"id":"legacy"}'::jsonb,
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        '2026-07-27T00:00:00Z',
                        '2026-07-27T00:00:00Z',
                        '00000000-0000-0000-0000-000000000172',
                        '00000000-0000-0000-0000-000000000172',
                        'NORMALIZED',
                        '2026-07-27T00:00:00Z'
                    FROM integration_connections connection
                    WHERE connection.connection_key = 'livesklad-default'
                    """.formatted(rawId, externalId));
        }
    }

    private void addVersion29Report(PostgreSQLContainer postgres)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO stores (
                        id, connection_id, source_system, external_id, name,
                        reporting_started_on
                    )
                    SELECT
                        '%s',
                        id,
                        'LIVESKLAD',
                        'report-payload-migration-store',
                        'Report payload migration store',
                        '2025-01-01'
                    FROM integration_connections
                    WHERE connection_key = 'livesklad-default'
                    """.formatted(REPORT_STORE_ID));
            statement.executeUpdate("""
                    INSERT INTO report_snapshots (
                        id,
                        store_id,
                        report_type,
                        period_type,
                        period_start,
                        period_end,
                        status,
                        template_version,
                        data_contract_version,
                        source_hash,
                        payload,
                        generated_at,
                        approved_at,
                        metadata,
                        schema_version,
                        revision,
                        payload_hash
                    ) VALUES (
                        '%s',
                        '%s',
                        'ANNUAL',
                        'YEAR',
                        '2025-01-01',
                        '2025-12-31',
                        'FINALIZED',
                        'migration-template-v1',
                        'migration-contract-v1',
                        repeat('a', 64),
                        '{"z":1,"a":{"y":2,"x":3}}'::jsonb,
                        '2026-01-01T00:00:00Z',
                        '2026-01-01T00:00:00Z',
                        '{}'::jsonb,
                        1,
                        1,
                        repeat('b', 64)
                    )
                    """.formatted(REPORT_ID, REPORT_STORE_ID));
        }
    }

    private void assertReportPayloadMigrated(PostgreSQLContainer postgres)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement(); ResultSet resultSet =
                     statement.executeQuery("""
                             SELECT
                                 pg_typeof(payload)::text AS payload_type,
                                 payload,
                                 payload_hash,
                                 encode(
                                     digest(convert_to(payload, 'UTF8'), 'sha256'),
                                     'hex'
                                 ) AS expected_hash
                             FROM report_snapshots
                             WHERE id = '%s'
                             """.formatted(REPORT_ID))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString("payload_type")).isEqualTo("text");
            assertThat(resultSet.getString("payload"))
                    .isEqualTo("{\"a\": {\"x\": 3, \"y\": 2}, \"z\": 1}");
            assertThat(resultSet.getString("payload_hash"))
                    .isEqualTo(resultSet.getString("expected_hash"));
        }
    }

    private void assertFinalizedReportRemainsImmutable(
            PostgreSQLContainer postgres
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                    UPDATE report_snapshots
                    SET payload = payload
                    WHERE id = '%s'
                    """.formatted(REPORT_ID)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(
                            "Finalized report snapshots cannot be updated"
                    );
        }
    }

    private int payloadPolicyVersion(
            PostgreSQLContainer postgres,
            String rawId
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement(); ResultSet resultSet =
                     statement.executeQuery(
                             "SELECT payload_policy_version "
                                     + "FROM raw_record_versions WHERE id = '"
                                     + rawId
                                     + "'"
                     )) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private String currentVersion(PostgreSQLContainer postgres)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement(); ResultSet resultSet =
                     statement.executeQuery("""
                             SELECT version
                             FROM flyway_schema_history
                             WHERE version IS NOT NULL AND success
                             ORDER BY installed_rank DESC
                             LIMIT 1
                             """)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString("version");
        }
    }

    private void resetSchema(PostgreSQLContainer postgres)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }
}
