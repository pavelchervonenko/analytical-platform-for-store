package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.metrics.service.StoreKpiService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class StoreKpiIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private StoreKpiService storeKpiService;

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
        jdbcTemplate.update("DELETE FROM data_quality_issues");
        jdbcTemplate.update("DELETE FROM sales_document_items");
        jdbcTemplate.update("DELETE FROM sales_documents");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM stores");
    }

    @Test
    void aggregatesSignedFactsAndAppliesStorePeriodAndDeletionFilters() {
        TestGraph graph = createGraph();
        UUID saleId = addDocument(graph, document(
                graph.storeId(), "sale-start", "SALE", PERIOD_START, "330.00", false, null
        ));
        addItem(graph, item(saleId, "main", "IPHONE_NEW_ASIS", "2.000", "300.00", knownCost("180.00")));
        addItem(graph, item(saleId, "unmapped", "UNMAPPED", "1.000", "20.00", knownCost("10.00")));
        addItem(graph, item(saleId, "zero-cost", "IPHONE_NEW_ASIS", "1.000", "10.00", unexpectedZeroCost()));
        addItem(graph, item(saleId, "excluded", "EXCLUDE", "10.000", "999.00", knownCost("500.00")));
        addItem(graph, item(saleId, "deleted-item", "IPHONE_NEW_ASIS", "5.000", "999.00", deletedKnownCost("500.00")));

        UUID returnId = addDocument(graph, document(
                graph.storeId(), "return-end", "RETURN", PERIOD_END, "50.00", false, saleId
        ));
        addItem(graph, item(returnId, "return-main", "IPHONE_NEW_ASIS", "0.500", "50.00", knownCost("30.00")));

        addIgnoredDocuments(graph);
        addQualityIssues(graph);

        StoreKpiResult result = storeKpiService.calculate(graph.storeId(), period());

        assertThat(result.netRevenue()).isEqualByComparingTo("280.00");
        assertThat(result.netQuantity()).isEqualByComparingTo("3.500");
        assertThat(result.costAmount()).isEqualByComparingTo("160.00");
        assertThat(result.grossProfit()).isEqualByComparingTo("120.00");
        assertThat(result.marginPercent()).isEqualByComparingTo("42.86");
        assertThat(result.dataQuality().completeCostData()).isTrue();
        assertThat(result.dataQuality().includedItemCount()).isEqualTo(4);
        assertThat(result.dataQuality().unmappedItemCount()).isOne();
        assertThat(result.dataQuality().missingCostItemCount()).isZero();
        assertThat(result.dataQuality().unexpectedZeroCostItemCount()).isOne();
        assertThat(result.dataQuality().storeOpenQualityIssueCount()).isOne();
    }

    @Test
    void marksCostDerivedMetricsUnknownWhenAnIncludedCostIsMissing() {
        TestGraph graph = createGraph();
        UUID saleId = addDocument(graph, document(
                graph.storeId(), "sale-missing-cost", "SALE", PERIOD_START, "80.00", false, null
        ));
        addItem(graph, item(saleId, "known", "IPHONE_NEW_ASIS", "1.000", "50.00", knownCost("20.00")));
        addItem(graph, item(saleId, "missing", "UNMAPPED", "1.000", "30.00", missingCost()));

        StoreKpiResult result = storeKpiService.calculate(graph.storeId(), period());

        assertThat(result.netRevenue()).isEqualByComparingTo("80.00");
        assertThat(result.netQuantity()).isEqualByComparingTo("2.000");
        assertThat(result.costAmount()).isNull();
        assertThat(result.grossProfit()).isNull();
        assertThat(result.marginPercent()).isNull();
        assertThat(result.dataQuality().completeCostData()).isFalse();
        assertThat(result.dataQuality().missingCostItemCount()).isOne();
    }

    @Test
    void returnsZerosAndNoMarginForPeriodWithoutFacts() {
        TestGraph graph = createGraph();

        StoreKpiResult result = storeKpiService.calculate(graph.storeId(), period());

        assertThat(result.netRevenue()).isEqualByComparingTo("0.00");
        assertThat(result.netQuantity()).isEqualByComparingTo("0.000");
        assertThat(result.costAmount()).isEqualByComparingTo("0.00");
        assertThat(result.grossProfit()).isEqualByComparingTo("0.00");
        assertThat(result.marginPercent()).isNull();
        assertThat(result.dataQuality().includedItemCount()).isZero();
    }

    private void addIgnoredDocuments(TestGraph graph) {
        UUID before = addDocument(graph, document(
                graph.storeId(), "before", "SALE", PERIOD_START.minusDays(1), "999.00", false, null
        ));
        addItem(graph, item(before, "before-item", "IPHONE_NEW_ASIS", "1.000", "999.00", knownCost("500.00")));

        UUID after = addDocument(graph, document(
                graph.storeId(), "after", "SALE", PERIOD_END.plusDays(1), "999.00", false, null
        ));
        addItem(graph, item(after, "after-item", "IPHONE_NEW_ASIS", "1.000", "999.00", knownCost("500.00")));

        UUID deleted = addDocument(graph, document(
                graph.storeId(), "deleted", "SALE", PERIOD_START.plusDays(1), "999.00", true, null
        ));
        addItem(graph, item(deleted, "deleted-document-item", "IPHONE_NEW_ASIS", "1.000", "999.00",
                knownCost("500.00")));

        UUID otherStore = addDocument(graph, document(
                graph.otherStoreId(), "other-store", "SALE", PERIOD_START, "999.00", false, null
        ));
        addItem(graph, item(otherStore, "other-store-item", "IPHONE_NEW_ASIS", "1.000", "999.00",
                knownCost("500.00")));
    }

    private void addQualityIssues(TestGraph graph) {
        jdbcTemplate.update(
                """
                INSERT INTO data_quality_issues (
                    store_id, entity_type, entity_id, issue_code, severity, status, message
                ) VALUES (?, 'SALE_ITEM', 'open-item', 'MISSING_COST', 'WARNING', 'OPEN', 'Open issue')
                """,
                graph.storeId()
        );
        jdbcTemplate.update(
                """
                INSERT INTO data_quality_issues (
                    store_id, entity_type, entity_id, issue_code, severity, status, message,
                    resolved_at
                ) VALUES (?, 'SALE_ITEM', 'resolved-item', 'MISSING_COST', 'WARNING', 'RESOLVED',
                          'Resolved issue', now())
                """,
                graph.storeId()
        );
        jdbcTemplate.update(
                """
                INSERT INTO data_quality_issues (
                    store_id, entity_type, entity_id, issue_code, severity, status, message
                ) VALUES (?, 'SALE_ITEM', 'other-item', 'MISSING_COST', 'WARNING', 'OPEN', 'Other issue')
                """,
                graph.otherStoreId()
        );
    }

    private TestGraph createGraph() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        addStore(storeId, connectionId, "kpi-store");
        addStore(otherStoreId, connectionId, "other-store");

        UUID syncRunId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_runs (
                    id, connection_id, source_system, trigger_type, sync_scope, status, started_at,
                    finished_at
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
                ) VALUES (?, ?, 'LIVESKLAD', 'kpi-product', 'KPI product', 'PRODUCT')
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

    private UUID addDocument(TestGraph graph, DocumentFixture document) {
        UUID documentId = UUID.randomUUID();
        Instant occurredAt = document.businessDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id, original_document_id,
                    document_kind, source_document_type, occurred_at, business_date, net_amount,
                    is_deleted, last_sync_run_id
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?, ?, 'sale', ?, ?, ?, ?, ?)
                """,
                documentId,
                graph.connectionId(),
                document.externalId(),
                document.storeId(),
                document.originalDocumentId(),
                document.kind(),
                Timestamp.from(occurredAt),
                document.businessDate(),
                document.netAmount(),
                document.deleted(),
                graph.syncRunId()
        );
        return documentId;
    }

    private void addItem(TestGraph graph, ItemFixture item) {
        UUID categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM analytics_categories WHERE code = ?",
                UUID.class,
                item.categoryCode()
        );
        jdbcTemplate.update(
                """
                INSERT INTO sales_document_items (
                    sales_document_id, external_id, product_id, product_name_snapshot,
                    analytics_category_id, condition_type_snapshot, quantity, unit_price,
                    gross_amount, discount_amount, net_amount, cost_amount, cost_quality,
                    is_deleted
                ) VALUES (?, ?, ?, 'KPI product', ?, 'NEW', ?, ?, ?, 0, ?, ?, ?, ?)
                """,
                item.documentId(),
                item.externalId(),
                graph.productId(),
                categoryId,
                item.quantity(),
                item.netAmount(),
                item.netAmount(),
                item.netAmount(),
                item.costAmount(),
                item.costQuality(),
                item.deleted()
        );
    }

    private DocumentFixture document(
            UUID storeId,
            String externalId,
            String kind,
            LocalDate businessDate,
            String netAmount,
            boolean deleted,
            UUID originalDocumentId
    ) {
        return new DocumentFixture(
                storeId,
                externalId,
                kind,
                businessDate,
                new BigDecimal(netAmount),
                deleted,
                originalDocumentId
        );
    }

    private ItemFixture item(
            UUID documentId,
            String externalId,
            String categoryCode,
            String quantity,
            String netAmount,
            ItemCost cost
    ) {
        return new ItemFixture(
                documentId,
                externalId,
                categoryCode,
                new BigDecimal(quantity),
                new BigDecimal(netAmount),
                cost.amount() == null ? null : new BigDecimal(cost.amount()),
                cost.quality(),
                cost.deleted()
        );
    }

    private ItemCost knownCost(String amount) {
        return new ItemCost(amount, "KNOWN", false);
    }

    private ItemCost deletedKnownCost(String amount) {
        return new ItemCost(amount, "KNOWN", true);
    }

    private ItemCost unexpectedZeroCost() {
        return new ItemCost("0.00", "ZERO_UNEXPECTED", false);
    }

    private ItemCost missingCost() {
        return new ItemCost(null, "MISSING", false);
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

    private record DocumentFixture(
            UUID storeId,
            String externalId,
            String kind,
            LocalDate businessDate,
            BigDecimal netAmount,
            boolean deleted,
            UUID originalDocumentId
    ) {
    }

    private record ItemCost(String amount, String quality, boolean deleted) {
    }

    private record ItemFixture(
            UUID documentId,
            String externalId,
            String categoryCode,
            BigDecimal quantity,
            BigDecimal netAmount,
            BigDecimal costAmount,
            String costQuality,
            boolean deleted
    ) {
    }
}
