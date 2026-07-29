package com.storeanalytics.salary.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
class PayrollSalesRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private PayrollSalesRepository repository;

    @Autowired
    private PayrollReadinessRepository readinessRepository;

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
        jdbcTemplate.update("DELETE FROM payroll_events");
        jdbcTemplate.update("DELETE FROM payroll_statements");
        jdbcTemplate.update("DELETE FROM payroll_adjustments");
        jdbcTemplate.update("DELETE FROM payroll_daily_allocations");
        jdbcTemplate.update("DELETE FROM payroll_daily_pools");
        jdbcTemplate.update("DELETE FROM payroll_runs");
        jdbcTemplate.update("DELETE FROM product_payroll_category_assignments");
        jdbcTemplate.update("DELETE FROM sales_document_items");
        jdbcTemplate.update("DELETE FROM sales_documents");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM stores");
    }

    @Test
    void attributesCrossMonthReturnsToReturnDayUsingOriginalSaleClassification() {
        TestGraph graph = createGraph();
        LocalDate saleDate = LocalDate.of(2026, 7, 10);
        UUID saleId = addDocument(graph, "salary-sale", "SALE", saleDate, null);
        addItem(saleId, graph.accessoryProductId(), "CASE_APPLE_IPHONE", "1.000", "100.00", "50.00");
        addItem(saleId, graph.playstationProductId(), "SETUP_SERVICE", "1.000", "200.00", "80.00");
        addItem(saleId, graph.macbookProductId(), "IPAD_MAC", "1.000", "1000.00", "700.00");

        UUID returnedId = addDocument(
                graph,
                "salary-return",
                "RETURN",
                LocalDate.of(2026, 8, 5),
                saleId
        );
        addItem(returnedId, graph.accessoryProductId(), "CASE_APPLE_IPHONE", "0.200", "20.00", "10.00");
        addItem(returnedId, graph.playstationProductId(), "SETUP_SERVICE", "0.200", "40.00", "20.00");
        replaceOverrideFrom(
                graph.playstationProductId(), LocalDate.of(2026, 8, 1), "SERVICE"
        );

        List<PayrollDailySalesAggregate> july = repository.aggregate(
                graph.storeId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)
        );

        assertThat(july).singleElement().satisfies(day -> {
            assertThat(day.workDate()).isEqualTo(saleDate);
            assertThat(day.netRevenue()).isEqualByComparingTo("1300.00");
            assertThat(day.accessoryTurnover()).isEqualByComparingTo("100.00");
            assertThat(day.serviceTurnover()).isEqualByComparingTo("0.00");
            assertThat(day.playstationGrossProfit()).isEqualByComparingTo("120.00");
            assertThat(day.paidRepairGrossProfit()).isEqualByComparingTo("0.00");
            assertThat(day.tier1Quantity()).isEqualByComparingTo("1.000");
            assertThat(day.tier2Quantity()).isEqualByComparingTo("0.000");
            assertThat(day.unmappedItemCount()).isZero();
            assertThat(day.missingCostItemCount()).isZero();
        });

        List<PayrollDailySalesAggregate> august = repository.aggregate(
                graph.storeId(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)
        );

        assertThat(august).singleElement().satisfies(day -> {
            assertThat(day.workDate()).isEqualTo(LocalDate.of(2026, 8, 5));
            assertThat(day.netRevenue()).isEqualByComparingTo("-60.00");
            assertThat(day.accessoryTurnover()).isEqualByComparingTo("-20.00");
            assertThat(day.serviceTurnover()).isEqualByComparingTo("0.00");
            assertThat(day.playstationGrossProfit()).isEqualByComparingTo("-20.00");
            assertThat(day.tier1Quantity()).isEqualByComparingTo("0.000");
            assertThat(day.unmappedItemCount()).isZero();
        });

        assertThat(repository.sourceFacts(
                graph.storeId(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)
        )).hasSize(2).allSatisfy(fact ->
                assertThat(fact.payrollDate()).isEqualTo(LocalDate.of(2026, 8, 5))
        ).anySatisfy(fact -> {
            assertThat(fact.productId()).isEqualTo(graph.playstationProductId());
            assertThat(fact.effectivePayrollCategory())
                    .isEqualTo("PLAYSTATION_SUBSCRIPTION");
        });
    }

    @Test
    void reportsReturnMonthCostIssueForCrossMonthReturn() {
        TestGraph graph = createGraph();
        UUID saleId = addDocument(
                graph, "cost-sale", "SALE", LocalDate.of(2026, 7, 10), null
        );
        addItem(
                saleId, graph.playstationProductId(), "SETUP_SERVICE",
                "1.000", "200.00", "80.00"
        );
        UUID returnId = addDocument(
                graph, "cost-return", "RETURN", LocalDate.of(2026, 8, 5), saleId
        );
        addItem(
                returnId, graph.playstationProductId(), "SETUP_SERVICE",
                "1.000", "200.00", null
        );

        assertThat(readinessRepository.missingCosts(
                graph.storeId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)
        )).isEmpty();
        assertThat(readinessRepository.missingCosts(
                graph.storeId(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)
        )).singleElement().satisfies(issue -> {
            assertThat(issue.payrollDate()).isEqualTo(LocalDate.of(2026, 8, 5));
            assertThat(issue.returnDocument()).isTrue();
        });
    }

    @Test
    void sourceFactsExposeRawAmountsAndEffectiveClassification() {
        TestGraph graph = createGraph();
        LocalDate saleDate = LocalDate.of(2026, 7, 10);
        UUID saleId = addDocument(graph, "fingerprint-sale", "SALE", saleDate, null);
        addItem(
                saleId,
                graph.playstationProductId(),
                "SETUP_SERVICE",
                "1.000",
                "200.00",
                "80.00"
        );

        List<PayrollSaleSourceFact> initial = repository.sourceFacts(
                graph.storeId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)
        );

        assertThat(initial).singleElement().satisfies(fact -> {
            assertThat(fact.payrollDate()).isEqualTo(saleDate);
            assertThat(fact.netAmount()).isEqualByComparingTo("200.00");
            assertThat(fact.basePayrollCategory()).isEqualTo("SERVICE");
            assertThat(fact.effectivePayrollCategory())
                    .isEqualTo("PLAYSTATION_SUBSCRIPTION");
            assertThat(fact.overrideAssignmentId()).isNotNull();
            assertThat(fact.excluded()).isFalse();
        });

        jdbcTemplate.update(
                "UPDATE sales_document_items SET net_amount = 225.00 "
                        + "WHERE sales_document_id = ?",
                saleId
        );
        List<PayrollSaleSourceFact> changed = repository.sourceFacts(
                graph.storeId(), LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)
        );
        assertThat(changed).singleElement()
                .extracting(PayrollSaleSourceFact::netAmount)
                .isEqualTo(new BigDecimal("225.00"));
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
                ) VALUES (?, ?, 'LIVESKLAD', 'salary-store', 'Salary store')
                """,
                storeId,
                connectionId
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
        UUID accessory = addProduct(connectionId, "salary-accessory", "Accessory");
        UUID playstation = addProduct(connectionId, "salary-ps-sub", "PS subscription");
        UUID macbook = addProduct(connectionId, "salary-macbook", "MacBook");
        addOverride(playstation, "PLAYSTATION_SUBSCRIPTION");
        addOverride(macbook, "TECH_TIER_1");
        return new TestGraph(
                connectionId, storeId, syncRunId, accessory, playstation, macbook
        );
    }

    private UUID addProduct(UUID connectionId, String externalId, String name) {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    id, connection_id, source_system, external_id, name, source_kind
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, 'PRODUCT')
                """,
                productId,
                connectionId,
                externalId,
                name
        );
        return productId;
    }

    private void addOverride(UUID productId, String payrollCategory) {
        jdbcTemplate.update(
                """
                INSERT INTO product_payroll_category_assignments (
                    product_id, payroll_category_code, valid_from, change_reason
                ) VALUES (?, ?, DATE '2026-07-01', 'Integration test')
                """,
                productId,
                payrollCategory
        );
    }

    private void replaceOverrideFrom(
            UUID productId,
            LocalDate validFrom,
            String payrollCategory
    ) {
        jdbcTemplate.update(
                "UPDATE product_payroll_category_assignments SET valid_to = ? "
                        + "WHERE product_id = ? AND valid_to IS NULL",
                validFrom,
                productId
        );
        jdbcTemplate.update(
                """
                INSERT INTO product_payroll_category_assignments (
                    product_id, payroll_category_code, valid_from, change_reason
                ) VALUES (?, ?, ?, 'Integration test reclassification')
                """,
                productId,
                payrollCategory,
                validFrom
        );
    }

    private UUID addDocument(
            TestGraph graph,
            String externalId,
            String kind,
            LocalDate businessDate,
            UUID originalDocumentId
    ) {
        UUID documentId = UUID.randomUUID();
        Instant occurredAt = businessDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
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
            UUID documentId,
            UUID productId,
            String categoryCode,
            String quantity,
            String netAmount,
            String costAmount
    ) {
        UUID categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM analytics_categories WHERE code = ?",
                UUID.class,
                categoryCode
        );
        BigDecimal net = new BigDecimal(netAmount);
        jdbcTemplate.update(
                """
                INSERT INTO sales_document_items (
                    sales_document_id, external_id, product_id, product_name_snapshot,
                    analytics_category_id, condition_type_snapshot, quantity, unit_price,
                    gross_amount, discount_amount, net_amount, cost_amount, cost_quality
                ) VALUES (?, ?, ?, 'Salary product', ?, 'NEW', ?, ?, ?, 0, ?, ?, 'KNOWN')
                """,
                documentId,
                UUID.randomUUID().toString(),
                productId,
                categoryId,
                new BigDecimal(quantity),
                net,
                net,
                net,
                costAmount == null ? null : new BigDecimal(costAmount)
        );
    }

    private record TestGraph(
            UUID connectionId,
            UUID storeId,
            UUID syncRunId,
            UUID accessoryProductId,
            UUID playstationProductId,
            UUID macbookProductId
    ) {
    }
}
