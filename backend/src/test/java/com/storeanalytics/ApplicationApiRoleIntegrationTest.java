package com.storeanalytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ApplicationRuntimeProperties;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.common.observability.SchemaVersionReadinessHealthIndicator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "app.runtime.role=API")
@Testcontainers(disabledWithoutDocker = true)
class ApplicationApiRoleIntegrationTest {

    private static final String RUNTIME_USER = "app_runtime_test";
    private static final String RUNTIME_PASSWORD = "runtime-test-password";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ApplicationRuntimeProperties runtimeProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SchemaVersionReadinessHealthIndicator schemaVersionReadiness;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations("classpath:db/migration")
                .load()
                .migrate();
        createRestrictedRuntimeRole();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> RUNTIME_USER);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
    }

    @Test
    void apiRoleStartsWithoutFlywayDdlOrWorkerOwnedBeans() {
        assertThat(runtimeProperties.role()).isEqualTo(ApplicationRole.API);
        assertThat(applicationContext.getBeansOfType(Flyway.class)).isEmpty();
        assertThat(applicationContext.getBeansWithAnnotation(
                ConditionalOnApplicationRole.class
        )).isEmpty();
        assertThat(schemaVersionReadiness.health().getStatus())
                .isEqualTo(Status.UP);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT has_schema_privilege(current_user, 'public', 'CREATE')",
                Boolean.class
        )).isFalse();
    }

    private static void createRestrictedRuntimeRole() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_USER
                    + " LOGIN PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("GRANT CONNECT ON DATABASE "
                    + POSTGRES.getDatabaseName() + " TO " + RUNTIME_USER);
            statement.execute("GRANT USAGE ON SCHEMA public TO "
                    + RUNTIME_USER);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE "
                    + "ON ALL TABLES IN SCHEMA public TO " + RUNTIME_USER);
            statement.execute("GRANT USAGE, SELECT, UPDATE "
                    + "ON ALL SEQUENCES IN SCHEMA public TO " + RUNTIME_USER);
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM "
                    + RUNTIME_USER);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Cannot prepare restricted runtime database role",
                    exception
            );
        }
    }
}
