package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.review.WeeklyReviewRevenueRepository.RevenueComparison;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
class WeeklyReviewRevenueRepositoryIntegrationTest {

    private static final LocalDate CURRENT_START = LocalDate.of(2026, 8, 17);
    private static final LocalDate CURRENT_END = LocalDate.of(2026, 8, 23);
    private static final LocalDate PREVIOUS_START = LocalDate.of(2026, 8, 10);
    private static final LocalDate PREVIOUS_END = LocalDate.of(2026, 8, 16);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklyReviewRevenueRepository repository;

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
    void separatesSalesAndReturnsForBothWeeksAndPreservesNetIdentity() {
        TestGraph graph = createGraph();
        addDocumentWithItem(graph, "current-sale", "SALE", CURRENT_START, "100.00", false, "IPHONE_NEW_ASIS");
        addDocumentWithItem(graph, "current-return", "RETURN", CURRENT_END, "15.00", false, "IPHONE_NEW_ASIS");
        addDocumentWithItem(graph, "previous-sale", "SALE", PREVIOUS_START, "90.00", false, "IPHONE_NEW_ASIS");
        addDocumentWithItem(graph, "previous-return", "RETURN", PREVIOUS_END, "5.00", false, "IPHONE_NEW_ASIS");
        addDocumentWithItem(graph, "deleted", "SALE", CURRENT_START, "999.00", true, "IPHONE_NEW_ASIS");
        addDocumentWithItem(graph, "excluded", "SALE", CURRENT_START, "700.00", false, "EXCLUDE");

        RevenueComparison result = repository.read(
                graph.storeId(),
                new StoreKpiPeriod(CURRENT_START, CURRENT_END),
                new StoreKpiPeriod(PREVIOUS_START, PREVIOUS_END)
        );

        assertThat(result.current().salesRevenue()).isEqualByComparingTo("100.00");
        assertThat(result.current().returnRevenue()).isEqualByComparingTo("15.00");
        assertThat(result.current().netRevenue()).isEqualByComparingTo("85.00");
        assertThat(result.current().saleDocumentCount()).isEqualTo(2);
        assertThat(result.current().returnDocumentCount()).isOne();
        assertThat(result.previous().salesRevenue()).isEqualByComparingTo("90.00");
        assertThat(result.previous().returnRevenue()).isEqualByComparingTo("5.00");
        assertThat(result.previous().netRevenue()).isEqualByComparingTo("85.00");
        assertThat(result.previous().saleDocumentCount()).isOne();
        assertThat(result.previous().returnDocumentCount()).isOne();
    }

    @Test
    void returnsZeroFactsForEmptyWeeks() {
        TestGraph graph = createGraph();

        RevenueComparison result = repository.read(
                graph.storeId(),
                new StoreKpiPeriod(CURRENT_START, CURRENT_END),
                new StoreKpiPeriod(PREVIOUS_START, PREVIOUS_END)
        );

        assertThat(result.current().salesRevenue()).isEqualByComparingTo("0.00");
        assertThat(result.current().returnRevenue()).isEqualByComparingTo("0.00");
        assertThat(result.current().saleDocumentCount()).isZero();
        assertThat(result.previous().netRevenue()).isEqualByComparingTo("0.00");
    }

    private TestGraph createGraph() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (id, connection_id, source_system, external_id, name)
                VALUES (?, ?, 'LIVESKLAD', ?, 'Weekly review store')
                """,
                storeId,
                connectionId,
                "weekly-review-" + storeId
        );
        UUID syncRunId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_runs (
                    id, connection_id, source_system, trigger_type, sync_scope,
                    status, started_at, finished_at
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
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Weekly review product', 'PRODUCT')
                """,
                productId,
                connectionId,
                "weekly-review-product-" + productId
        );
        return new TestGraph(connectionId, storeId, syncRunId, productId);
    }

    private void addDocumentWithItem(
            TestGraph graph,
            String externalId,
            String kind,
            LocalDate businessDate,
            String netAmount,
            boolean deleted,
            String categoryCode
    ) {
        UUID documentId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id,
                    document_kind, source_document_type, occurred_at, business_date,
                    net_amount, is_deleted, last_sync_run_id
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?, 'sale', ?, ?, ?, ?, ?)
                """,
                documentId,
                graph.connectionId(),
                externalId,
                graph.storeId(),
                kind,
                Timestamp.from(businessDate.atStartOfDay(ZoneOffset.UTC).toInstant()),
                businessDate,
                new BigDecimal(netAmount),
                deleted,
                graph.syncRunId()
        );
        UUID categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM analytics_categories WHERE code = ?",
                UUID.class,
                categoryCode
        );
        jdbcTemplate.update(
                """
                INSERT INTO sales_document_items (
                    sales_document_id, external_id, product_id, product_name_snapshot,
                    analytics_category_id, condition_type_snapshot, quantity, unit_price,
                    gross_amount, discount_amount, net_amount, cost_amount, cost_quality,
                    is_deleted
                ) VALUES (?, ?, ?, 'Weekly review product', ?, 'NEW', 1, ?, ?, 0, ?, 1, 'KNOWN', false)
                """,
                documentId,
                externalId + "-item",
                graph.productId(),
                categoryId,
                new BigDecimal(netAmount),
                new BigDecimal(netAmount),
                new BigDecimal(netAmount)
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
