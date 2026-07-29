package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.service.CategoryKpiEntry;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.CategoryKpiService;
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
class CategoryKpiIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private CategoryKpiService categoryKpiService;

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
        jdbcTemplate.update("UPDATE analytics_categories SET is_active = true");
    }

    @Test
    void returnsSignedCategoryFactsAndConfirmedBusinessGroups() {
        TestGraph graph = createGraph();
        UUID saleId = addDocument(graph, document(
                graph.storeId(), "sale", "SALE", PERIOD_START, false, null
        ));
        addItem(graph, item(
                saleId, "iphone", "IPHONE_NEW_ASIS", "2.000", "300.00", knownCost("180.00")
        ));
        addItem(graph, item(
                saleId, "charger", "CHARGER_CABLE", "1.000", "30.00", knownCost("10.00")
        ));
        addItem(graph, item(
                saleId, "unmapped", "UNMAPPED", "1.000", "20.00", knownCost("10.00")
        ));
        addItem(graph, item(
                saleId, "excluded", "EXCLUDE", "1.000", "999.00", knownCost("500.00")
        ));
        addItem(graph, item(
                saleId, "deleted", "IPHONE_NEW_ASIS", "1.000", "999.00", deletedCost("500.00")
        ));

        UUID returnId = addDocument(graph, document(
                graph.storeId(), "return", "RETURN", PERIOD_END, false, saleId
        ));
        addItem(graph, item(
                returnId, "iphone-return", "IPHONE_NEW_ASIS",
                "0.500", "50.00", knownCost("30.00")
        ));

        addIgnoredFacts(graph);
        jdbcTemplate.update(
                "UPDATE analytics_categories SET is_active = false WHERE code = 'IPHONE_USED'"
        );

        CategoryKpiResult result = categoryKpiService.calculate(graph.storeId(), period());

        assertThat(result.categories()).hasSize(19);
        assertThat(result.categories())
                .extracting(CategoryKpiEntry::categoryCode)
                .doesNotContain("EXCLUDE");

        CategoryKpiEntry iphone = category(result, "IPHONE_NEW_ASIS");
        assertThat(iphone.metrics().netRevenue()).isEqualByComparingTo("250.00");
        assertThat(iphone.metrics().netQuantity()).isEqualByComparingTo("1.500");
        assertThat(iphone.metrics().costAmount()).isEqualByComparingTo("150.00");
        assertThat(iphone.metrics().grossProfit()).isEqualByComparingTo("100.00");
        assertThat(iphone.metrics().marginPercent()).isEqualByComparingTo("40.00");
        assertThat(iphone.metrics().dataQuality().includedItemCount()).isEqualTo(2);

        CategoryKpiEntry charger = category(result, "CHARGER_CABLE");
        assertThat(charger.metrics().netRevenue()).isEqualByComparingTo("30.00");
        assertThat(charger.metrics().marginPercent()).isEqualByComparingTo("66.67");
        assertThat(category(result, "UNMAPPED").metrics().netRevenue())
                .isEqualByComparingTo("20.00");

        CategoryKpiEntry inactiveWithoutFacts = category(result, "IPHONE_USED");
        assertThat(inactiveWithoutFacts.categoryActive()).isFalse();
        assertThat(inactiveWithoutFacts.metrics().netRevenue()).isEqualByComparingTo("0.00");
        assertThat(inactiveWithoutFacts.metrics().marginPercent()).isNull();

        assertThat(group(result, "PHONES").metrics().netRevenue())
                .isEqualByComparingTo("250.00");
        assertThat(group(result, "DEVICES").metrics().netRevenue())
                .isEqualByComparingTo("250.00");
        assertThat(group(result, "ADDITIONAL_REVENUE").metrics().netRevenue())
                .isEqualByComparingTo("30.00");
    }

    @Test
    void isolatesMissingAndUnexpectedZeroCostQualityByCategoryAndGroup() {
        TestGraph graph = createGraph();
        UUID saleId = addDocument(graph, document(
                graph.storeId(), "quality-sale", "SALE", PERIOD_START, false, null
        ));
        addItem(graph, item(
                saleId, "charger", "CHARGER_CABLE", "1.000", "20.00", knownCost("10.00")
        ));
        addItem(graph, item(
                saleId, "setup", "SETUP_SERVICE", "1.000", "30.00", missingCost()
        ));
        addItem(graph, item(
                saleId, "pods", "PODS_WATCH_OTHER_DEVICE",
                "1.000", "100.00", unexpectedZeroCost()
        ));

        CategoryKpiResult result = categoryKpiService.calculate(graph.storeId(), period());

        assertThat(category(result, "CHARGER_CABLE").metrics().costAmount())
                .isEqualByComparingTo("10.00");
        assertThat(category(result, "SETUP_SERVICE").metrics().costAmount()).isNull();
        assertThat(category(result, "SETUP_SERVICE").metrics().dataQuality().missingCostItemCount())
                .isOne();

        CategoryKpiGroup additional = group(result, "ADDITIONAL_REVENUE");
        assertThat(additional.metrics().netRevenue()).isEqualByComparingTo("50.00");
        assertThat(additional.metrics().costAmount()).isNull();
        assertThat(additional.metrics().grossProfit()).isNull();
        assertThat(additional.metrics().dataQuality().completeCostData()).isFalse();

        CategoryKpiGroup devices = group(result, "DEVICES");
        assertThat(devices.metrics().costAmount()).isEqualByComparingTo("0.00");
        assertThat(devices.metrics().grossProfit()).isEqualByComparingTo("100.00");
        assertThat(devices.metrics().dataQuality().unexpectedZeroCostItemCount()).isOne();
    }

    private void addIgnoredFacts(TestGraph graph) {
        UUID beforeId = addDocument(graph, document(
                graph.storeId(), "before", "SALE", PERIOD_START.minusDays(1), false, null
        ));
        addItem(graph, item(
                beforeId, "before-item", "IPHONE_NEW_ASIS",
                "1.000", "999.00", knownCost("500.00")
        ));

        UUID otherStoreId = addDocument(graph, document(
                graph.otherStoreId(), "other-store", "SALE", PERIOD_START, false, null
        ));
        addItem(graph, item(
                otherStoreId, "other-item", "IPHONE_NEW_ASIS",
                "1.000", "999.00", knownCost("500.00")
        ));

        UUID deletedId = addDocument(graph, document(
                graph.storeId(), "deleted-document", "SALE", PERIOD_START, true, null
        ));
        addItem(graph, item(
                deletedId, "deleted-document-item", "IPHONE_NEW_ASIS",
                "1.000", "999.00", knownCost("500.00")
        ));
    }

    private TestGraph createGraph() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        addStore(storeId, connectionId, "category-kpi-store");
        addStore(otherStoreId, connectionId, "category-kpi-other-store");

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
                ) VALUES (?, ?, 'LIVESKLAD', 'category-kpi-product', 'Category KPI product', 'PRODUCT')
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
        Instant occurredAt = document.businessDate()
                .atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant();
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
                document.externalId(),
                document.storeId(),
                document.originalDocumentId(),
                document.kind(),
                Timestamp.from(occurredAt),
                document.businessDate(),
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
                ) VALUES (?, ?, ?, 'Category KPI product', ?, 'NEW', ?, ?, ?, 0, ?, ?, ?, ?)
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
            boolean deleted,
            UUID originalDocumentId
    ) {
        return new DocumentFixture(
                storeId,
                externalId,
                kind,
                businessDate,
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

    private ItemCost deletedCost(String amount) {
        return new ItemCost(amount, "KNOWN", true);
    }

    private ItemCost missingCost() {
        return new ItemCost(null, "MISSING", false);
    }

    private ItemCost unexpectedZeroCost() {
        return new ItemCost("0.00", "ZERO_UNEXPECTED", false);
    }

    private CategoryKpiEntry category(CategoryKpiResult result, String code) {
        return result.categories().stream()
                .filter(entry -> entry.categoryCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private CategoryKpiGroup group(CategoryKpiResult result, String code) {
        return result.groups().stream()
                .filter(group -> group.groupCode().equals(code))
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

    private record DocumentFixture(
            UUID storeId,
            String externalId,
            String kind,
            LocalDate businessDate,
            boolean deleted,
            UUID originalDocumentId
    ) {
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

    private record ItemCost(String amount, String quality, boolean deleted) {
    }
}
