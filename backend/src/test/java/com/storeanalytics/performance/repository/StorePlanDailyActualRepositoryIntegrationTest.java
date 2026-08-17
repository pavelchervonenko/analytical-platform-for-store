package com.storeanalytics.performance.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StorePlanDailyActualRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private StorePlanDailyActualRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM sales_document_items");
        jdbcTemplate.update("DELETE FROM sales_documents");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM stores");
    }

    @Test
    void aggregatesSignedDailyRevenueAccessoriesAndAllServiceKinds() {
        TestGraph graph = createGraph();
        LocalDate saleDate = LocalDate.of(2026, 8, 1);
        UUID saleId = addDocument(graph, "daily-sale", "SALE", saleDate, null);
        addItem(graph, saleId, "IPHONE_NEW_ASIS", "1000.00", false);
        addItem(graph, saleId, "CHARGER_CABLE", "100.00", false);
        addItem(graph, saleId, "SETUP_SERVICE", "200.00", false);
        addItem(graph, saleId, "WARRANTY_GENERIC", "50.00", false);
        addItem(graph, saleId, "EXCLUDE", "999.00", false);
        addItem(graph, saleId, "CHARGER_CABLE", "777.00", true);

        LocalDate returnDate = saleDate.plusDays(1);
        UUID returnId = addDocument(
                graph,
                "daily-return",
                "RETURN",
                returnDate,
                saleId
        );
        addItem(graph, returnId, "CHARGER_CABLE", "25.00", false);
        addItem(graph, returnId, "SETUP_SERVICE", "50.00", false);

        List<StorePlanDailyActual> result = repository.aggregate(
                graph.storeId(),
                saleDate,
                returnDate
        );

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).satisfies(day -> {
            assertThat(day.businessDate()).isEqualTo(saleDate);
            assertThat(day.revenueAmount()).isEqualByComparingTo("1350.00");
            assertThat(day.accessoryAmount()).isEqualByComparingTo("100.00");
            assertThat(day.serviceAmount()).isEqualByComparingTo("250.00");
        });
        assertThat(result.get(1)).satisfies(day -> {
            assertThat(day.businessDate()).isEqualTo(returnDate);
            assertThat(day.revenueAmount()).isEqualByComparingTo("-75.00");
            assertThat(day.accessoryAmount()).isEqualByComparingTo("-25.00");
            assertThat(day.serviceAmount()).isEqualByComparingTo("-50.00");
        });
    }

    private TestGraph createGraph() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Daily plan store')
                """,
                storeId,
                connectionId,
                "daily-plan-" + storeId
        );
        UUID syncRunId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_runs (
                    id, connection_id, source_system, trigger_type, sync_scope, status,
                    started_at, finished_at
                ) VALUES (?, ?, 'LIVESKLAD', 'MANUAL', 'SALES', 'SUCCESS', now(), now())
                """,
                syncRunId,
                connectionId
        );
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    id, connection_id, source_system, external_id, name, source_kind
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Daily plan product', 'PRODUCT')
                """,
                productId,
                connectionId,
                "daily-plan-product-" + productId
        );
        return new TestGraph(connectionId, storeId, syncRunId, productId);
    }

    private UUID addDocument(
            TestGraph graph,
            String externalId,
            String kind,
            LocalDate businessDate,
            UUID originalDocumentId
    ) {
        UUID documentId = UUID.randomUUID();
        Instant occurredAt = businessDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id,
                    original_document_id, document_kind, source_document_type,
                    occurred_at, business_date, net_amount, last_sync_run_id
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?, ?, 'sale', ?, ?, 0, ?)
                """,
                documentId,
                graph.connectionId(),
                externalId,
                graph.storeId(),
                originalDocumentId,
                kind,
                Timestamp.from(occurredAt),
                businessDate,
                graph.syncRunId()
        );
        return documentId;
    }

    private void addItem(
            TestGraph graph,
            UUID documentId,
            String categoryCode,
            String netAmount,
            boolean deleted
    ) {
        UUID categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM analytics_categories WHERE code = ?",
                UUID.class,
                categoryCode
        );
        BigDecimal amount = new BigDecimal(netAmount);
        jdbcTemplate.update(
                """
                INSERT INTO sales_document_items (
                    sales_document_id, external_id, product_id, product_name_snapshot,
                    analytics_category_id, condition_type_snapshot, quantity, unit_price,
                    gross_amount, discount_amount, net_amount, cost_amount, cost_quality,
                    is_deleted
                ) VALUES (?, ?, ?, 'Daily plan product', ?, 'NEW', 1, ?, ?, 0, ?, 0, 'KNOWN', ?)
                """,
                documentId,
                UUID.randomUUID().toString(),
                graph.productId(),
                categoryId,
                amount,
                amount,
                amount,
                deleted
        );
    }

    private record TestGraph(
            UUID connectionId,
            UUID storeId,
            UUID syncRunId,
            UUID productId
    ) {
    }
}
