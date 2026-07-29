package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.service.AverageKpiResult;
import com.storeanalytics.metrics.service.AverageKpiService;
import com.storeanalytics.metrics.service.AverageMetricComparison;
import com.storeanalytics.metrics.service.CategoryAverageEntry;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AverageKpiIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 10);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 12);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private AverageKpiService averageKpiService;

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
        jdbcTemplate.update("DELETE FROM product_category_assignments");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM stores");
    }

    @Test
    void calculatesCurrentAndPreviousAveragesWithReturnsAndFilters() {
        TestGraph graph = createGraph();

        UUID previousSale = addDocument(
                graph, "previous-sale", "SALE", PERIOD_START.minusDays(1),
                null, graph.storeId(), false
        );
        addItem(graph, previousSale, "previous-phone", "IPHONE_NEW_ASIS", "1.000", "500.00", false);
        addItem(graph, previousSale, "previous-charger", "CHARGER_CABLE", "1.000", "100.00", false);

        UUID currentSaleOne = addDocument(
                graph, "current-sale-one", "SALE", PERIOD_START,
                null, graph.storeId(), false
        );
        addItem(graph, currentSaleOne, "phone-one", "IPHONE_NEW_ASIS", "2.000", "1000.00", false);
        addItem(graph, currentSaleOne, "charger-one", "CHARGER_CABLE", "3.000", "300.00", false);
        addItem(graph, currentSaleOne, "excluded", "EXCLUDE", "1.000", "9999.00", false);
        addItem(graph, currentSaleOne, "deleted-item", "CHARGER_CABLE", "1.000", "9999.00", true);

        UUID currentSaleTwo = addDocument(
                graph, "current-sale-two", "SALE", PERIOD_START.plusDays(1),
                null, graph.storeId(), false
        );
        addItem(graph, currentSaleTwo, "phone-two", "IPHONE_NEW_ASIS", "1.000", "500.00", false);
        addItem(graph, currentSaleTwo, "charger-two", "CHARGER_CABLE", "1.000", "200.00", false);

        UUID currentReturn = addDocument(
                graph, "current-return", "RETURN", PERIOD_END,
                currentSaleOne, graph.storeId(), false
        );
        addItem(graph, currentReturn, "returned-phone", "IPHONE_NEW_ASIS", "0.500", "200.00", false);
        addItem(graph, currentReturn, "returned-charger", "CHARGER_CABLE", "0.500", "50.00", false);

        addIgnoredFacts(graph);

        AverageKpiResult result = averageKpiService.calculate(graph.storeId(), period());

        assertThat(result.previousPeriodStart()).isEqualTo(LocalDate.of(2026, 7, 7));
        assertThat(result.previousPeriodEnd()).isEqualTo(LocalDate.of(2026, 7, 9));
        assertComparison(
                result.averageReceipt(),
                expected("1750.00", "2", "875", "600.00", "1", "600", "45.8")
        );
        assertComparison(
                result.additionalRevenuePerPhone(),
                expected(
                        "450.00", "2.500", "180", "100.00", "1.000", "100", "80.0"
                )
        );
        assertThat(result.categoryAveragePrices()).hasSize(19);
        assertThat(result.categoryAveragePrices())
                .extracting(CategoryAverageEntry::categoryCode)
                .doesNotContain("EXCLUDE");
        assertComparison(
                category(result, "CHARGER_CABLE").averageUnitPrice(),
                expected(
                        "450.00", "3.500", "129", "100.00", "1.000", "100", "28.6"
                )
        );
        assertComparison(
                category(result, "IPHONE_NEW_ASIS").averageUnitPrice(),
                expected(
                        "1300.00", "2.500", "520", "500.00", "1.000", "500", "4.0"
                )
        );
        assertThat(category(result, "CASE_APPLE_IPHONE").averageUnitPrice().current().value())
                .isNull();
    }

    @Test
    void returnsZeroInputsAndNullAveragesWhenBothPeriodsHaveNoFacts() {
        TestGraph graph = createGraph();

        AverageKpiResult result = averageKpiService.calculate(graph.storeId(), period());

        assertThat(result.categoryAveragePrices()).hasSize(19);
        assertThat(result.averageReceipt().current().numerator()).isEqualByComparingTo("0.00");
        assertThat(result.averageReceipt().current().denominator()).isEqualByComparingTo("0");
        assertThat(result.averageReceipt().current().value()).isNull();
        assertThat(result.averageReceipt().changePercent()).isNull();
        assertThat(result.additionalRevenuePerPhone().current().denominator())
                .isEqualByComparingTo("0.000");
        assertThat(result.additionalRevenuePerPhone().current().value()).isNull();
        assertThat(result.categoryAveragePrices())
                .allSatisfy(category -> {
                    assertThat(category.averageUnitPrice().current().value()).isNull();
                    assertThat(category.averageUnitPrice().previous().value()).isNull();
                    assertThat(category.averageUnitPrice().changePercent()).isNull();
                });
    }

    private void addIgnoredFacts(TestGraph graph) {
        UUID otherStoreSale = addDocument(
                graph, "other-store", "SALE", PERIOD_START,
                null, graph.otherStoreId(), false
        );
        addItem(graph, otherStoreSale, "other-phone", "IPHONE_NEW_ASIS", "10.000", "10000.00", false);

        UUID deletedSale = addDocument(
                graph, "deleted-sale", "SALE", PERIOD_START,
                null, graph.storeId(), true
        );
        addItem(graph, deletedSale, "deleted-phone", "IPHONE_NEW_ASIS", "10.000", "10000.00", false);

        UUID outsideSale = addDocument(
                graph, "outside-sale", "SALE", PERIOD_START.minusDays(4),
                null, graph.storeId(), false
        );
        addItem(graph, outsideSale, "outside-phone", "IPHONE_NEW_ASIS", "10.000", "10000.00", false);
    }

    private TestGraph createGraph() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        addStore(storeId, connectionId, "average-store");
        addStore(otherStoreId, connectionId, "average-other-store");

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
                ) VALUES (?, ?, 'LIVESKLAD', 'average-product', 'Average product', 'PRODUCT')
                """,
                productId,
                connectionId
        );
        return new TestGraph(connectionId, storeId, otherStoreId, syncRunId, productId);
    }

    private void addStore(UUID storeId, UUID connectionId, String externalId) {
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?)
                """,
                storeId,
                connectionId,
                externalId,
                externalId
        );
    }

    private UUID addDocument(
            TestGraph graph,
            String externalId,
            String kind,
            LocalDate businessDate,
            UUID originalDocumentId,
            UUID storeId,
            boolean deleted
    ) {
        UUID documentId = UUID.randomUUID();
        Instant occurredAt = businessDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id,
                    original_document_id, document_kind, source_document_type, occurred_at,
                    business_date, net_amount, is_deleted, last_sync_run_id
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?, ?, 'sale', ?, ?, 0, ?, ?)
                """,
                documentId,
                graph.connectionId(),
                externalId,
                storeId,
                originalDocumentId,
                kind,
                Timestamp.from(occurredAt),
                businessDate,
                deleted,
                graph.syncRunId()
        );
        return documentId;
    }

    private void addItem(
            TestGraph graph,
            UUID documentId,
            String externalId,
            String categoryCode,
            String quantity,
            String netAmount,
            boolean deleted
    ) {
        UUID categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM analytics_categories WHERE code = ?",
                UUID.class,
                categoryCode
        );
        BigDecimal parsedQuantity = new BigDecimal(quantity);
        BigDecimal parsedNetAmount = new BigDecimal(netAmount);
        BigDecimal unitPrice = parsedNetAmount.divide(parsedQuantity);
        jdbcTemplate.update(
                """
                INSERT INTO sales_document_items (
                    sales_document_id, external_id, product_id, product_name_snapshot,
                    analytics_category_id, condition_type_snapshot, quantity, unit_price,
                    gross_amount, discount_amount, net_amount, cost_amount, cost_quality,
                    is_deleted
                ) VALUES (?, ?, ?, 'Average product', ?, 'NOT_APPLICABLE', ?, ?, ?, 0, ?, 0,
                    'KNOWN', ?)
                """,
                documentId,
                externalId,
                graph.productId(),
                categoryId,
                parsedQuantity,
                unitPrice,
                parsedNetAmount,
                parsedNetAmount,
                deleted
        );
    }

    private void assertComparison(
            AverageMetricComparison comparison,
            ExpectedComparison expected
    ) {
        assertThat(comparison.current().numerator())
                .isEqualByComparingTo(expected.currentNumerator());
        assertThat(comparison.current().denominator())
                .isEqualByComparingTo(expected.currentDenominator());
        assertThat(comparison.current().value())
                .isEqualByComparingTo(expected.currentValue());
        assertThat(comparison.previous().numerator())
                .isEqualByComparingTo(expected.previousNumerator());
        assertThat(comparison.previous().denominator())
                .isEqualByComparingTo(expected.previousDenominator());
        assertThat(comparison.previous().value())
                .isEqualByComparingTo(expected.previousValue());
        assertThat(comparison.changePercent())
                .isEqualByComparingTo(expected.changePercent());
    }

    private ExpectedComparison expected(
            String currentNumerator,
            String currentDenominator,
            String currentValue,
            String previousNumerator,
            String previousDenominator,
            String previousValue,
            String changePercent
    ) {
        return new ExpectedComparison(
                currentNumerator, currentDenominator, currentValue,
                previousNumerator, previousDenominator, previousValue, changePercent
        );
    }

    private CategoryAverageEntry category(AverageKpiResult result, String code) {
        return result.categoryAveragePrices().stream()
                .filter(category -> category.categoryCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }

    private record TestGraph(
            UUID connectionId,
            UUID storeId,
            UUID otherStoreId,
            UUID syncRunId,
            UUID productId
    ) {
    }

    private record ExpectedComparison(
            String currentNumerator,
            String currentDenominator,
            String currentValue,
            String previousNumerator,
            String previousDenominator,
            String previousValue,
            String changePercent
    ) {
    }
}
