package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class MigrationLeastPrivilegeIntegrationTest {

    private static final String MIGRATOR = "restricted_migrator";
    private static final String PASSWORD = "restricted-migrator-password";

    @Test
    void upgradesFromProductionSchemaWithoutTemporaryTablePrivilege()
            throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                "postgres:16-alpine"
        )) {
            postgres.start();
            prepareRestrictedMigrator(postgres);

            Flyway baseline = flyway(postgres, "34");
            baseline.migrate();
            assertThat(hasTemporaryPrivilege(postgres)).isFalse();

            Flyway upgrade = flyway(postgres, null);
            upgrade.migrate();

            assertThat(upgrade.info().current().getVersion().getVersion())
                    .isEqualTo("41");
            assertThat(hasTemporaryPrivilege(postgres)).isFalse();
        }
    }

    private void prepareRestrictedMigrator(PostgreSQLContainer postgres)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            statement.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
            statement.execute("REVOKE TEMPORARY ON DATABASE "
                    + postgres.getDatabaseName() + " FROM PUBLIC");
            statement.execute("CREATE ROLE " + MIGRATOR
                    + " LOGIN PASSWORD '" + PASSWORD + "'");
            statement.execute("GRANT CONNECT, CREATE ON DATABASE "
                    + postgres.getDatabaseName() + " TO " + MIGRATOR);
            statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO "
                    + MIGRATOR);
        }
    }

    private Flyway flyway(PostgreSQLContainer postgres, String target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), MIGRATOR, PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private boolean hasTemporaryPrivilege(PostgreSQLContainer postgres)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), MIGRATOR, PASSWORD
        ); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT has_database_privilege(
                         current_user,
                         current_database(),
                         'TEMPORARY'
                     )
                     """)) {
            assertThat(result.next()).isTrue();
            return result.getBoolean(1);
        }
    }
}
