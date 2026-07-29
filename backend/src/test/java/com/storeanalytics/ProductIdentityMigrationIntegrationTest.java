package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class ProductIdentityMigrationIntegrationTest {

    @Test
    void v19AllowsOnlyOneClaimOfAV18ProvisionalIdentity()
            throws SQLException {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                "postgres:16-alpine"
        )) {
            postgres.start();
            migrate(postgres, "18");
            insertV18Products(postgres);

            migrate(postgres, null);

            assertThat(update(
                    postgres,
                    """
                    UPDATE products
                    SET external_id = 'product-4310',
                        source_kind = 'PRODUCT'
                    WHERE external_id = '4310'
                    """
            )).isOne();
            assertIdentityChangeRejected(
                    postgres,
                    """
                    UPDATE products
                    SET external_id = 'different-4310'
                    WHERE external_id = 'product-4310'
                    """
            );
            assertIdentityChangeRejected(
                    postgres,
                    """
                    UPDATE products
                    SET external_id = 'different-final'
                    WHERE external_id = 'product-final'
                    """
            );
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

    private void insertV18Products(PostgreSQLContainer postgres)
            throws SQLException {
        update(
                postgres,
                """
                INSERT INTO products (
                    connection_id,
                    source_system,
                    external_id,
                    code,
                    name,
                    source_kind
                )
                SELECT id, 'LIVESKLAD', '4310', '4310',
                       'Provisional cable', 'UNKNOWN'
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """
        );
        update(
                postgres,
                """
                INSERT INTO products (
                    connection_id,
                    source_system,
                    external_id,
                    code,
                    name,
                    source_kind
                )
                SELECT id, 'LIVESKLAD', 'product-final', '5035',
                       'Final product', 'PRODUCT'
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """
        );
    }

    private int update(PostgreSQLContainer postgres, String sql)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ); Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private void assertIdentityChangeRejected(
            PostgreSQLContainer postgres,
            String sql
    ) {
        assertThatThrownBy(() -> update(postgres, sql))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("source identity cannot be changed");
    }
}
