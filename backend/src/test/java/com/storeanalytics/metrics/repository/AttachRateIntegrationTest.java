package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.service.AttachRateEntry;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.AttachRateService;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AttachRateIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AttachRateService attachRateService;

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
    void countsOnlySameDocumentAdditionsAgainstAllRelevantDevices() {
        TestGraph graph = createGraph();

        UUID iphoneWithAdditions = addSale(graph, "iphone-with-additions", PERIOD_START);
        addItem(graph, iphoneWithAdditions, "iphone", "IPHONE_NEW_ASIS", "1.000", "NEW");
        addItem(graph, iphoneWithAdditions, "cases", "CASE_APPLE_IPHONE", "2.000", "NOT_APPLICABLE");
        addItem(graph, iphoneWithAdditions, "charger", "CHARGER_CABLE", "1.000", "NOT_APPLICABLE");
        addItem(
                graph, iphoneWithAdditions, "wrong-glass", "GLASS_CAMERA_SAMSUNG",
                "1.000", "NOT_APPLICABLE"
        );

        UUID iphoneOnly = addSale(graph, "iphone-only", PERIOD_START.plusDays(1));
        addItem(graph, iphoneOnly, "iphone", "IPHONE_NEW_ASIS", "1.000", "ASIS");

        UUID orphanCase = addSale(graph, "orphan-case", PERIOD_START.plusDays(2));
        addItem(graph, orphanCase, "case", "CASE_APPLE_IPHONE", "1.000", "NOT_APPLICABLE");

        UUID samsung = addSale(graph, "samsung", PERIOD_START.plusDays(3));
        addItem(graph, samsung, "phone", "SAMSUNG_USED", "1.000", "USED");
        addItem(graph, samsung, "case", "CASE_SAMSUNG", "1.000", "NOT_APPLICABLE");

        UUID pods = addSale(graph, "pods", PERIOD_START.plusDays(4));
        addItem(graph, pods, "devices", "PODS_WATCH_OTHER_DEVICE", "2.000", "NEW");
        addItem(graph, pods, "accessory", "ACCESSORY_PODS_WATCH", "1.000", "NOT_APPLICABLE");

        UUID usedIpad = addSale(graph, "used-ipad", PERIOD_START.plusDays(5));
        addItem(graph, usedIpad, "device", "IPAD_MAC", "1.000", "USED");
        addItem(graph, usedIpad, "accessories", "ACCESSORY_IPAD_MAC", "2.000", "NOT_APPLICABLE");

        UUID newDevice = addSale(graph, "new-device", PERIOD_START.plusDays(6));
        addItem(graph, newDevice, "device", "PODS_WATCH_OTHER_DEVICE", "1.000", "NEW");
        addItem(graph, newDevice, "protection", "PREMIUM_PROTECTION", "2.000", "NOT_APPLICABLE");
        addItem(graph, newDevice, "warranty", "WARRANTY_GENERIC", "1.000", "NOT_APPLICABLE");

        UUID usedDevice = addSale(graph, "used-device", PERIOD_START.plusDays(7));
        addItem(graph, usedDevice, "device", "IPAD_MAC", "1.000", "USED");
        addItem(graph, usedDevice, "warranty", "WARRANTY_GENERIC", "1.000", "NOT_APPLICABLE");

        addOtherStoreFacts(graph);

        AttachRateResult result = attachRateService.calculate(graph.storeId(), period());

        assertThat(result.rates()).hasSize(12);
        assertRate(result, "CASE_APPLE_IPHONE", "2.000", "2.000", "100.0");
        assertRate(result, "CHARGER_CABLE", "1.000", "3.000", "33.3");
        assertRate(result, "CASE_SAMSUNG", "1.000", "1.000", "100.0");
        assertRate(result, "ACCESSORY_PODS_WATCH", "1.000", "3.000", "33.3");
        assertRate(result, "ACCESSORY_IPAD_MAC", "2.000", "2.000", "100.0");
        assertRate(result, "PREMIUM_PROTECTION", "2.000", "5.000", "40.0");
        assertRate(result, "WARRANTY_GENERIC_NEW", "1.000", "5.000", "20.0");
        assertRate(result, "WARRANTY_GENERIC_USED", "1.000", "3.000", "33.3");
        assertThat(result.dataQuality().unmatchedNumeratorItemCount()).isEqualTo(2);
        assertThat(result.dataQuality().ambiguousWarrantyItemCount()).isZero();
        assertThat(result.dataQuality().unknownDeviceConditionItemCount()).isZero();
    }

    @Test
    void appliesReturnsUsingOriginalSaleAsAttachContext() {
        TestGraph graph = createGraph();
        UUID historicalSale = addSale(graph, "historical-sale", PERIOD_START.minusDays(10));
        addItem(graph, historicalSale, "iphone", "IPHONE_NEW_ASIS", "1.000", "NEW");
        addItem(graph, historicalSale, "case", "CASE_APPLE_IPHONE", "1.000", "NOT_APPLICABLE");

        UUID currentSale = addSale(graph, "current-sale", PERIOD_START);
        addItem(graph, currentSale, "iphones", "IPHONE_NEW_ASIS", "2.000", "NEW");
        addItem(graph, currentSale, "cases", "CASE_APPLE_IPHONE", "2.000", "NOT_APPLICABLE");

        UUID caseReturn = addReturn(graph, "case-return", PERIOD_START.plusDays(1), historicalSale);
        addItem(graph, caseReturn, "case", "CASE_APPLE_IPHONE", "1.000", "NOT_APPLICABLE");

        UUID deviceReturn = addReturn(graph, "device-return", PERIOD_START.plusDays(2), currentSale);
        addItem(graph, deviceReturn, "iphone", "IPHONE_NEW_ASIS", "1.000", "NEW");

        AttachRateResult result = attachRateService.calculate(graph.storeId(), period());

        assertRate(result, "CASE_APPLE_IPHONE", "1.000", "1.000", "100.0");
        assertThat(result.dataQuality().unmatchedNumeratorItemCount()).isZero();
    }

    @Test
    void reportsAmbiguousWarrantyUnmatchedAdditionAndUnknownDeviceCondition() {
        TestGraph graph = createGraph();

        UUID mixedCondition = addSale(graph, "mixed-condition", PERIOD_START);
        addItem(graph, mixedCondition, "new", "IPHONE_NEW_ASIS", "1.000", "NEW");
        addItem(graph, mixedCondition, "used", "SAMSUNG_USED", "1.000", "USED");
        addItem(graph, mixedCondition, "warranty", "WARRANTY_GENERIC", "1.000", "NOT_APPLICABLE");

        UUID noDevice = addSale(graph, "no-device", PERIOD_START.plusDays(1));
        addItem(graph, noDevice, "warranty", "WARRANTY_GENERIC", "1.000", "NOT_APPLICABLE");

        UUID unknownCondition = addSale(graph, "unknown-condition", PERIOD_START.plusDays(2));
        addItem(
                graph, unknownCondition, "device", "PODS_WATCH_OTHER_DEVICE",
                "1.000", "UNKNOWN"
        );
        addItem(
                graph, unknownCondition, "protection", "PREMIUM_PROTECTION",
                "1.000", "NOT_APPLICABLE"
        );

        AttachRateResult result = attachRateService.calculate(graph.storeId(), period());

        assertThat(rate(result, "WARRANTY_GENERIC_NEW").numeratorQuantity())
                .isEqualByComparingTo("0.000");
        assertThat(rate(result, "WARRANTY_GENERIC_USED").numeratorQuantity())
                .isEqualByComparingTo("0.000");
        assertThat(rate(result, "PREMIUM_PROTECTION").numeratorQuantity())
                .isEqualByComparingTo("0.000");
        assertThat(result.dataQuality().unmatchedNumeratorItemCount()).isEqualTo(2);
        assertThat(result.dataQuality().ambiguousWarrantyItemCount()).isOne();
        assertThat(result.dataQuality().unknownDeviceConditionItemCount()).isOne();
    }

    private void addOtherStoreFacts(TestGraph graph) {
        UUID documentId = addDocument(
                graph,
                "other-store",
                "SALE",
                PERIOD_START,
                null,
                graph.otherStoreId()
        );
        addItem(graph, documentId, "iphone", "IPHONE_NEW_ASIS", "100.000", "NEW");
        addItem(graph, documentId, "case", "CASE_APPLE_IPHONE", "100.000", "NOT_APPLICABLE");
    }

    private TestGraph createGraph() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        addStore(storeId, connectionId, "attach-rate-store");
        addStore(otherStoreId, connectionId, "attach-rate-other-store");

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
                ) VALUES (?, ?, 'LIVESKLAD', 'attach-product', 'Attach product', 'PRODUCT')
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

    private UUID addSale(TestGraph graph, String externalId, LocalDate businessDate) {
        return addDocument(
                graph,
                externalId,
                "SALE",
                businessDate,
                null,
                graph.storeId()
        );
    }

    private UUID addReturn(
            TestGraph graph,
            String externalId,
            LocalDate businessDate,
            UUID originalDocumentId
    ) {
        return addDocument(
                graph,
                externalId,
                "RETURN",
                businessDate,
                originalDocumentId,
                graph.storeId()
        );
    }

    private UUID addDocument(
            TestGraph graph,
            String externalId,
            String kind,
            LocalDate businessDate,
            UUID originalDocumentId,
            UUID storeId
    ) {
        UUID documentId = UUID.randomUUID();
        Instant occurredAt = businessDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id,
                    original_document_id, document_kind, source_document_type, occurred_at,
                    business_date, net_amount, is_deleted, last_sync_run_id
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?, ?, 'sale', ?, ?, 0, false, ?)
                """,
                documentId,
                graph.connectionId(),
                externalId,
                storeId,
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
            String externalId,
            String categoryCode,
            String quantity,
            String conditionType
    ) {
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
                ) VALUES (?, ?, ?, 'Attach product', ?, ?, ?, 0, 0, 0, 0, 0, 'KNOWN', false)
                """,
                documentId,
                externalId,
                graph.productId(),
                categoryId,
                conditionType,
                new BigDecimal(quantity)
        );
    }

    private void assertRate(
            AttachRateResult result,
            String metricCode,
            String numerator,
            String denominator,
            String rate
    ) {
        AttachRateEntry entry = rate(result, metricCode);
        assertThat(entry.numeratorQuantity()).isEqualByComparingTo(numerator);
        assertThat(entry.denominatorQuantity()).isEqualByComparingTo(denominator);
        assertThat(entry.ratePerHundred()).isEqualByComparingTo(rate);
    }

    private AttachRateEntry rate(AttachRateResult result, String metricCode) {
        return result.rates().stream()
                .filter(entry -> entry.metricCode().equals(metricCode))
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
}
