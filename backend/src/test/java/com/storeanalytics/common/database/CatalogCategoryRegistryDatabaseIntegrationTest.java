package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.product.service.CatalogCategoryRegistry;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class CatalogCategoryRegistryDatabaseIntegrationTest {

    private static final Set<String> NEW_LEAVES = Set.of(
            "PHONE_OTHER", "TABLET_APPLE", "TABLET_OTHER", "LAPTOP_APPLE", "LAPTOP_OTHER",
            "WATCH_APPLE", "WATCH_SAMSUNG", "WATCH_OTHER", "GAME_CONSOLES",
            "MICROPHONES", "GAMING_ACCESSORIES", "ACCESSORY_AIRPODS",
            "ACCESSORY_APPLE_WATCH", "CASE_UNIVERSAL", "GLASS_OTHER",
            "GLASS_PHONE_UNRESOLVED", "PROTECTIVE_FILM", "PACKAGING"
    );

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void everyStandardCategoryHasTheApprovedMonetaryFlagsInDatabase() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load().migrate();

        // Read-only diagnostic: catalog leaves are active and PS5 keeps its approved salary tier.
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var risk = statement.executeQuery("""
                     SELECT code, is_active,
                         resolve_default_payroll_category(code, 'Sony PlayStation 5',
                             payroll_category_code) AS effective_payroll,
                         resolve_default_payroll_category(code, 'Sony PlayStation 4',
                             payroll_category_code) AS other_payroll
                     FROM analytics_categories
                     WHERE code IN ('PODS_WATCH_OTHER_DEVICE', 'GAME_CONSOLES')
                     ORDER BY code
                     """)) {
            assertThat(risk.next()).isTrue();
            assertThat(risk.getString("code")).isEqualTo("GAME_CONSOLES");
            assertThat(risk.getBoolean("is_active")).as("approved prospective category is active").isTrue();
            assertThat(risk.getString("effective_payroll")).isEqualTo("TECH_TIER_1");
            assertThat(risk.getString("other_payroll")).isEqualTo("TECH_TIER_2");
            assertThat(risk.next()).isTrue();
            assertThat(risk.getString("code")).isEqualTo("PODS_WATCH_OTHER_DEVICE");
            assertThat(risk.getString("effective_payroll")).isEqualTo("TECH_TIER_1");
        }

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement();
             var phone = statement.executeQuery("""
                     SELECT payroll_category_code, counts_as_phone, is_active
                     FROM analytics_categories WHERE code = 'PHONE_OTHER'
                     """)) {
            assertThat(phone.next()).isTrue();
            assertThat(phone.getString("payroll_category_code")).isEqualTo("TECH_TIER_1");
            assertThat(phone.getBoolean("counts_as_phone")).isTrue();
            assertThat(phone.getBoolean("is_active")).isTrue();
            assertThat(phone.next()).isFalse();
        }

        var registry = CatalogCategoryRegistry.standard();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT category_kind, device_family, counts_as_phone, counts_as_device,
                            counts_as_additional_revenue, is_active
                     FROM analytics_categories WHERE code = ?
                     """)) {
            for (var definition : registry.definitions()) {
                if (definition.scope() != CatalogCategoryRegistry.Scope.STANDARD) {
                    continue;
                }
                statement.setString(1, definition.code());
                try (ResultSet actual = statement.executeQuery()) {
                    assertThat(actual.next()).as("missing %s", definition.code()).isTrue();
                    assertThat(actual.getString("category_kind"))
                            .as(definition.code()).isEqualTo(definition.categoryKind().name());
                    assertThat(actual.getString("device_family"))
                            .as(definition.code()).isEqualTo(definition.deviceFamily().name());
                    assertThat(actual.getBoolean("counts_as_phone"))
                            .as(definition.code()).isEqualTo(definition.countsAsPhone());
                    assertThat(actual.getBoolean("counts_as_device"))
                            .as(definition.code()).isEqualTo(definition.countsAsDevice());
                    assertThat(actual.getBoolean("counts_as_additional_revenue"))
                            .as(definition.code())
                            .isEqualTo(definition.countsAsAdditionalRevenue());
                    if (NEW_LEAVES.contains(definition.code())) {
                        assertThat(actual.getBoolean("is_active"))
                                .as("approved catalog leaf is active: %s", definition.code())
                                .isTrue();
                    }
                    assertThat(actual.next()).as("duplicate %s", definition.code()).isFalse();
                }
            }
        }
    }
}
