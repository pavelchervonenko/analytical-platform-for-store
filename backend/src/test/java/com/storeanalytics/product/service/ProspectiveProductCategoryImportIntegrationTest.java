package com.storeanalytics.product.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.audit.service.AuditLogService;
import com.storeanalytics.auth.model.AppUser;
import com.storeanalytics.auth.model.UserRole;
import com.storeanalytics.auth.repository.AppUserRepository;
import com.storeanalytics.integration.connection.repository.IntegrationConnectionRepository;
import com.storeanalytics.product.exception.ProductClassificationConflictException;
import com.storeanalytics.product.model.ProductConditionType;
import com.storeanalytics.product.repository.ProductCategoryAssignmentRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ProspectiveProductCategoryImportIntegrationTest {
    private static final Instant BOUNDARY = Instant.parse("2030-01-01T00:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired private ApplicationContext context;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProductCategoryImportService initialImport;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void replacesOnlyFutureIntervalAndIsIdempotent() {
        UUID actor = users.saveAndFlush(new AppUser(
                "prospective-import-" + UUID.randomUUID() + "@example.invalid",
                "{noop}test-password-hash", "Prospective import", UserRole.ADMIN
        )).getId();
        initialImport.importAssignments(command(Instant.parse("2025-12-31T22:00:00Z"),
                "CHARGER_CABLE"), actor);

        ProductCategoryImportService prospective = new ProductCategoryImportService(
                context.getBean(IntegrationConnectionRepository.class),
                context.getBean(LiveSkladProductIdentityResolver.class),
                context.getBean(ProductCategoryAssignmentRepository.class),
                context.getBean(EntityManager.class),
                context.getBean(AuditLogService.class),
                context.getBean(ProductClassificationReconciliationService.class),
                new CatalogClassificationCutover(BOUNDARY.toString())
        );
        ProductCategoryImportCommand replacement = command(BOUNDARY, "OTHER_ACCESSORY_PRODUCT");
        var transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThat(transactions.execute(status -> prospective.importAssignments(replacement, actor))
                .assignmentsCreated()).isOne();
        assertThat(transactions.execute(status -> prospective.importAssignments(replacement, actor))
                .assignmentsUnchanged()).isOne();
        assertThat(jdbc.queryForList("""
                SELECT category.code
                FROM product_category_assignments assignment
                JOIN analytics_categories category ON category.id=assignment.analytics_category_id
                JOIN products product ON product.id=assignment.product_id
                WHERE product.external_id='prospective-adapter'
                ORDER BY assignment.valid_from
                """, String.class)).containsExactly("CHARGER_CABLE", "OTHER_ACCESSORY_PRODUCT");
        assertThat(jdbc.queryForObject("""
                SELECT valid_to
                FROM product_category_assignments assignment
                JOIN products product ON product.id=assignment.product_id
                WHERE product.external_id='prospective-adapter'
                  AND assignment.valid_from < '2030-01-01T00:00:00Z'
                """, java.sql.Timestamp.class).toInstant()).isEqualTo(BOUNDARY);

        assertThatThrownBy(() -> transactions.execute(status -> prospective.importAssignments(
                command(BOUNDARY, "CASE_OTHER_DEVICE"), actor)))
                .isInstanceOf(ProductClassificationConflictException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM product_category_assignments",
                Integer.class)).isEqualTo(2);
    }

    private ProductCategoryImportCommand command(Instant from, String category) {
        return new ProductCategoryImportCommand("livesklad-default", from,
                "prospective-test-v1", "Exact catalog review",
                List.of(new ProductCategoryImportEntry("prospective-adapter",
                        "Prospective adapter", category, ProductConditionType.NOT_APPLICABLE)));
    }
}
