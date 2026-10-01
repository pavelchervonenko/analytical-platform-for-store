package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.product.service.CatalogClassificationCutover;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class CatalogActivationStateIntegrationTest {
    @Test
    void boundaryIsExplicitImmutableAndCheckedAtRuntime() {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            CatalogActivationState.verify(jdbc, null);
            assertThatThrownBy(() -> CatalogActivationState.register(jdbc, Instant.EPOCH))
                    .hasMessageContaining("business-day midnight");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_classification_activation", Integer.class))
                    .isZero();
            Instant boundary = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Kaliningrad"))
                    .plusDays(30).atStartOfDay(java.time.ZoneId.of("Europe/Kaliningrad")).toInstant();
            CatalogActivationState.register(jdbc, boundary);
            CatalogActivationState.register(jdbc, boundary);
            var policy = new CatalogClassificationCutover(boundary.toString(), jdbc);
            assertThat(policy.isHistorical(boundary.minusNanos(1))).isTrue();
            assertThat(policy.isHistorical(boundary)).isFalse();
            assertThatThrownBy(() -> new CatalogClassificationCutover("", jdbc))
                    .hasMessageContaining("CATALOG_ACTIVATION_MISMATCH");
            assertThatThrownBy(() -> CatalogActivationState.register(jdbc, boundary.plusSeconds(86_400)))
                    .hasMessageContaining("CATALOG_ACTIVATION_MISMATCH");
            for (String sql : new String[]{
                    "UPDATE catalog_classification_activation SET activate_from = activate_from + interval '1 day'",
                    "DELETE FROM catalog_classification_activation",
                    "TRUNCATE catalog_classification_activation"
            }) {
                assertThatThrownBy(() -> jdbc.execute(sql)).hasStackTraceContaining("boundary is immutable");
            }
            CatalogActivationState.verify(jdbc, boundary);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM catalog_classification_activation", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void configurationRequiresOffsetAndExactDatabasePrecision() {
        assertThat(CatalogActivationState.parse("2026-10-01T03:00:00+03:00"))
                .isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThatThrownBy(() -> CatalogActivationState.parse("2026-10-01"))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
        assertThatThrownBy(() -> CatalogActivationState.parse("2026-10-01T00:00:00.000000001Z"))
                .hasMessageContaining("microsecond precision");
        assertThatThrownBy(() -> CatalogActivationState.requireBusinessDayBoundary(
                Instant.parse("2026-10-01T00:00:00Z")))
                .hasMessageContaining("business-day midnight");
        assertThat(CatalogActivationState.requireBusinessDayBoundary(
                Instant.parse("2026-09-30T22:00:00Z")))
                .isEqualTo(Instant.parse("2026-09-30T22:00:00Z"));
    }
}
