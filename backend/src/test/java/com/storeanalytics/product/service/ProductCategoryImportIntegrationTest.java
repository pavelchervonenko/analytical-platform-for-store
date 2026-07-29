package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.product.exception.ProductClassificationConflictException;
import com.storeanalytics.product.model.ProductConditionType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProductCategoryImportIntegrationTest {

    private static final Instant VALID_FROM = Instant.parse("2025-12-31T22:00:00Z");
    private static final String RULE_VERSION = "customer-approved-2026-07-20-v1";

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ProductCategoryImportService importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserRepository userRepository;

    private AppUser actor;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM product_category_assignments");
        jdbcTemplate.update("DELETE FROM products");
        actor = userRepository.saveAndFlush(new AppUser(
                "category-import-" + UUID.randomUUID() + "@example.invalid",
                "{noop}test-password-hash",
                "Category Import Admin",
                UserRole.ADMIN
        ));
    }

    @Test
    void importsMissingProductsAndAssignmentsIdempotently() {
        ProductCategoryImportCommand command = command(List.of(
                entry("4310", "Cable", "CHARGER_CABLE", ProductConditionType.NOT_APPLICABLE),
                entry("5035", "Setup", "SETUP_SERVICE", ProductConditionType.NOT_APPLICABLE)
        ));

        ProductCategoryImportResult first = importAssignments(command);
        ProductCategoryImportResult second = importAssignments(command);

        assertThat(first).isEqualTo(new ProductCategoryImportResult(2, 2, 2, 0));
        assertThat(second).isEqualTo(new ProductCategoryImportResult(2, 0, 0, 2));
        assertThat(count("products")).isEqualTo(2);
        assertThat(count("product_category_assignments")).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT source_kind FROM products WHERE external_id = '4310'",
                String.class
        )).isEqualTo("UNKNOWN");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT assignment_source
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                WHERE product.external_id = '4310'
                """,
                String.class
        )).isEqualTo("INITIAL_IMPORT");
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT valid_from
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                WHERE product.external_id = '4310'
                """,
                Timestamp.class
        ).toInstant()).isEqualTo(VALID_FROM);
    }

    @Test
    void permitsOnlyOneTimeClaimOfProvisionalLiveSkladIdentity() {
        importAssignments(command(List.of(
                entry(
                        "4310",
                        "Cable",
                        "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE
                )
        )));

        assertThat(jdbcTemplate.update(
                """
                UPDATE products
                SET external_id = 'nomenclature-4310',
                    source_kind = 'PRODUCT'
                WHERE external_id = '4310'
                """
        )).isOne();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT category.code
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                JOIN analytics_categories category
                  ON category.id = assignment.analytics_category_id
                WHERE product.external_id = 'nomenclature-4310'
                """,
                String.class
        )).isEqualTo("CHARGER_CABLE");

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE products
                SET external_id = 'different-identity'
                WHERE external_id = 'nomenclature-4310'
                """
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("source identity cannot be changed");
    }

    @Test
    void resolvesApprovedCatalogIdentifierByLiveSkladProductCode() {
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    connection_id, source_system, external_id, code, name, source_kind
                )
                SELECT id, 'LIVESKLAD', 'nomenclature-4310', '4310',
                       'LiveSklad Cable', 'PRODUCT'
                FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """
        );

        ProductCategoryImportResult result = importAssignments(command(List.of(
                entry(
                        "4310",
                        "Approved Cable",
                        "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE
                )
        )));

        assertThat(result).isEqualTo(
                new ProductCategoryImportResult(1, 0, 1, 0)
        );
        assertThat(count("products")).isOne();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT category.code
                FROM product_category_assignments assignment
                JOIN products product ON product.id = assignment.product_id
                JOIN analytics_categories category
                  ON category.id = assignment.analytics_category_id
                WHERE product.external_id = 'nomenclature-4310'
                """,
                String.class
        )).isEqualTo("CHARGER_CABLE");
    }

    @Test
    void rejectsConflictingHistoryAndRollsBackEntireBatch() {
        importAssignments(command(List.of(
                entry("4310", "Cable", "CHARGER_CABLE", ProductConditionType.NOT_APPLICABLE)
        )));
        ProductCategoryImportCommand conflict = command(List.of(
                entry("4310", "Cable", "OTHER_ACCESSORY_PRODUCT",
                        ProductConditionType.NOT_APPLICABLE),
                entry("new-product", "New product", "CHARGER_CABLE",
                        ProductConditionType.NOT_APPLICABLE)
        ));

        assertThatThrownBy(() -> importAssignments(conflict))
                .isInstanceOf(ProductClassificationConflictException.class)
                .hasMessageContaining("conflicting category history");

        assertThat(count("products")).isOne();
        assertThat(count("product_category_assignments")).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products WHERE external_id = 'new-product'",
                Integer.class
        )).isZero();
    }

    @Test
    void rejectsUnknownAndUnmappedCategoriesBeforeWriting() {
        assertThatThrownBy(() -> importAssignments(command(List.of(
                entry("unknown-category", "Unknown", "DOES_NOT_EXIST",
                        ProductConditionType.UNKNOWN)
        )))).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Unknown or inactive analytics categories");

        assertThatThrownBy(() -> importAssignments(command(List.of(
                entry("unmapped", "Unmapped", "UNMAPPED", ProductConditionType.UNKNOWN)
        )))).isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("absence of a category assignment");

        assertThat(count("products")).isZero();
        assertThat(count("product_category_assignments")).isZero();
    }

    private ProductCategoryImportResult importAssignments(
            ProductCategoryImportCommand command
    ) {
        return importService.importAssignments(command, actor.getId());
    }

    private ProductCategoryImportCommand command(List<ProductCategoryImportEntry> entries) {
        return new ProductCategoryImportCommand(
                "livesklad-default",
                VALID_FROM,
                RULE_VERSION,
                "Initial customer-approved classification",
                entries
        );
    }

    private ProductCategoryImportEntry entry(
            String externalId,
            String name,
            String categoryCode,
            ProductConditionType conditionType
    ) {
        return new ProductCategoryImportEntry(
                externalId,
                name,
                categoryCode,
                conditionType
        );
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
