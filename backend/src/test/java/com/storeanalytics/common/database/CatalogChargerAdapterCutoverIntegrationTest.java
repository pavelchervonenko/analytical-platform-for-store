package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Legacy v4 heuristics must not change old sales when the prospective boundary is recorded. */
@Testcontainers(disabledWithoutDocker = true)
class CatalogChargerAdapterCutoverIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void broadLegacyRuleStopsAtImmutableBoundary() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()).locations("classpath:db/migration").load().migrate();

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            assertThat(matches(statement, "2026-09-01T10:00:00Z", "Переходник USB-C"))
                    .isTrue();
            statement.execute("""
                    INSERT INTO catalog_classification_activation(singleton,activate_from)
                    VALUES (true,'2030-01-01T00:00:00Z')
                    """);
            assertThat(matches(statement, "2026-09-01T10:00:00Z", "Переходник USB-C"))
                    .isTrue();
            assertThat(matches(statement, "2030-01-01T00:00:00Z", "Переходник USB-C"))
                    .isFalse();
            assertThat(matches(statement, "2030-01-01T00:00:00Z", "Зарядный адаптер USB-C"))
                    .isTrue();
            assertThat(matches(statement, "2030-01-01T00:00:00Z", "Зарядный адаптер USB-C",
                    "CHARGER_CABLE")).isFalse();
        }
    }

    private boolean matches(java.sql.Statement statement, String occurredAt, String name)
            throws Exception {
        return matches(statement, occurredAt, name, "OTHER_ACCESSORY_PRODUCT");
    }

    private boolean matches(java.sql.Statement statement, String occurredAt, String name,
                            String category) throws Exception {
        try (var result = statement.executeQuery("SELECT catalog_charger_adapter_metric('"
                + category + "','" + name + "','" + occurredAt + "'::timestamptz)")) {
            result.next();
            return result.getBoolean(1);
        }
    }
}
