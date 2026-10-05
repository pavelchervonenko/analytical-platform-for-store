package com.storeanalytics.performance.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.service.OverviewMetricScope;
import com.storeanalytics.metrics.service.OverviewMetricsResult;
import com.storeanalytics.metrics.service.OverviewMetricsService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private OverviewMetricsService overview;

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
        jdbcTemplate.update("DELETE FROM employee_store_assignments");
        jdbcTemplate.update("DELETE FROM employees");
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

    @Test
    void sellerScopeIncludesOnlyActiveRankingParticipantsAndTheirReturns() {
        TestGraph graph = createGraph();
        UUID sellerId = addEmployee(graph, "Seller", true, true, true);
        UUID wholesaleBuyerId = addEmployee(graph, "Wholesale", true, true, false);
        LocalDate saleDate = LocalDate.of(2026, 8, 1);
        UUID sellerSaleId = addDocument(
                graph, "seller-sale", "SALE", saleDate, null, sellerId
        );
        addItem(graph, sellerSaleId, "CHARGER_CABLE", "100.00", false);
        UUID wholesaleSaleId = addDocument(
                graph, "wholesale-sale", "SALE", saleDate, null, wholesaleBuyerId
        );
        addItem(graph, wholesaleSaleId, "CHARGER_CABLE", "500.00", false);
        UUID unassignedSaleId = addDocument(
                graph, "unassigned-sale", "SALE", saleDate, null, null
        );
        addItem(graph, unassignedSaleId, "CHARGER_CABLE", "900.00", false);
        LocalDate returnDate = saleDate.plusDays(1);
        UUID sellerReturnId = addDocument(
                graph,
                "seller-return",
                "RETURN",
                returnDate,
                sellerSaleId,
                sellerId
        );
        addItem(graph, sellerReturnId, "CHARGER_CABLE", "20.00", false);
        setReturnProcessor(sellerReturnId, sellerId);

        List<StorePlanDailyActual> result = repository.aggregate(
                graph.storeId(),
                saleDate,
                returnDate,
                OverviewMetricScope.SELLERS
        );

        assertThat(result).hasSize(2);
        assertThat(result.get(0).revenueAmount()).isEqualByComparingTo("100.00");
        assertThat(result.get(1).revenueAmount()).isEqualByComparingTo("-20.00");
        assertMatchesOverview(graph, saleDate, returnDate, OverviewMetricScope.SELLERS);
    }

    @Test
    void usesReturnProcessorAndOwnMonthRegardlessOfOriginalSaleAuthorOrLink() {
        TestGraph graph = createGraph();
        UUID seller = addEmployee(graph, "Processor", true, true, true);
        UUID outside = addEmployee(graph, "Outside", true, true, false);
        LocalDate returnDate = LocalDate.of(2026, 8, 1);
        UUID original = addDocument(graph, "old-original", "SALE", returnDate.minusDays(1), null, outside);
        addItem(graph, original, "CHARGER_CABLE", "100.00", false);
        UUID returnId = addDocument(graph, "different-author", "RETURN", returnDate, original, outside);
        setReturnProcessor(returnId, seller);
        addItem(graph, returnId, "CHARGER_CABLE", "25.00", false);
        addItem(graph, returnId, "SETUP_SERVICE", "50.00", false);
        addItem(graph, returnId, "EXCLUDE", "999.00", false);
        addItem(graph, returnId, "SETUP_SERVICE", "888.00", true);
        UUID inverse = addDocument(graph, "outside-processor", "RETURN", returnDate, null, seller);
        setReturnProcessor(inverse, outside);
        addItem(graph, inverse, "CHARGER_CABLE", "200.00", false);
        UUID orphan = addDocument(graph, "known-orphan", "RETURN", returnDate.plusDays(1), null, null);
        setReturnProcessor(orphan, seller);
        addItem(graph, orphan, "WARRANTY_GENERIC", "10.00", false);

        List<StorePlanDailyActual> daily = repository.aggregate(
                graph.storeId(), returnDate, returnDate.plusDays(1), OverviewMetricScope.SELLERS);
        assertThat(daily).hasSize(2);
        assertThat(daily.getFirst().revenueAmount()).isEqualByComparingTo("-75.00");
        assertThat(daily.getFirst().accessoryAmount()).isEqualByComparingTo("-25.00");
        assertThat(daily.getFirst().serviceAmount()).isEqualByComparingTo("-50.00");
        assertThat(daily.getLast().serviceAmount()).isEqualByComparingTo("-10.00");
        assertMatchesOverview(graph, returnDate, returnDate.plusDays(1), OverviewMetricScope.SELLERS);
        assertMatchesOverview(graph, returnDate, returnDate.plusDays(1), OverviewMetricScope.STORE);
        assertThat(repository.aggregate(graph.storeId(), returnDate, returnDate).getFirst().revenueAmount())
                .isEqualByComparingTo("-275.00");
        assertThat(jdbcTemplate.queryForObject("SELECT employee_id FROM sales_documents WHERE id = ?",
                UUID.class, returnId)).isEqualTo(outside);

        jdbcTemplate.update("UPDATE sales_documents SET is_deleted = true WHERE id = ?", original);
        assertThat(repository.aggregate(graph.storeId(), returnDate, returnDate.plusDays(1),
                OverviewMetricScope.SELLERS)).isEqualTo(daily);
        jdbcTemplate.update("UPDATE sales_documents SET is_deleted = true WHERE id = ?", returnId);
        assertThat(repository.aggregate(graph.storeId(), returnDate, returnDate.plusDays(1),
                OverviewMetricScope.SELLERS)).containsExactly(daily.getLast());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "UNRESOLVED", "INACTIVE_EMPLOYEE", "INACTIVE_ASSIGNMENT", "NON_RANKING"})
    void unknownOrIneligibleProcessorNeverFallsBackToStoredSeller(String state) {
        TestGraph graph = createGraph();
        UUID originalSeller = addEmployee(graph, "Original", true, true, true);
        UUID processor = addEmployee(graph, "Processor", !state.equals("INACTIVE_EMPLOYEE"),
                !state.equals("INACTIVE_ASSIGNMENT"), !state.equals("NON_RANKING"));
        LocalDate date = LocalDate.of(2026, 8, 2);
        UUID returnId = addDocument(graph, "unknown-return", "RETURN", date, null, originalSeller);
        if (!state.equals("MISSING")) {
            setReturnProcessor(returnId, processor);
        }
        if (state.equals("UNRESOLVED")) {
            jdbcTemplate.update("UPDATE sales_documents SET attach_source_employee_external_id = 'not-imported' "
                    + "WHERE id = ?", returnId);
        }
        addItem(graph, returnId, "CHARGER_CABLE", "20.00", false);
        assertThat(repository.aggregate(graph.storeId(), date, date, OverviewMetricScope.SELLERS)).isEmpty();
        assertThat(repository.aggregate(graph.storeId(), date, date).getFirst().revenueAmount())
                .isEqualByComparingTo("-20.00");
        assertMatchesOverview(graph, date, date, OverviewMetricScope.SELLERS);
        assertMatchesOverview(graph, date, date, OverviewMetricScope.STORE);
        assertThat(jdbcTemplate.queryForObject("SELECT employee_id FROM sales_documents WHERE id = ?",
                UUID.class, returnId)).isEqualTo(originalSeller);
    }

    private void assertMatchesOverview(
            TestGraph graph, LocalDate start, LocalDate end, OverviewMetricScope scope
    ) {
        OverviewMetricsResult total = overview.calculate(graph.storeId(), new StoreKpiPeriod(start, end), scope);
        List<StorePlanDailyActual> daily = repository.aggregate(graph.storeId(), start, end, scope);
        assertThat(daily.stream().map(StorePlanDailyActual::revenueAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(total.netRevenue());
        assertThat(daily.stream().map(StorePlanDailyActual::accessoryAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(total.accessory().netRevenue());
        assertThat(daily.stream().map(StorePlanDailyActual::serviceAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(total.service().netRevenue());
    }

    private void setReturnProcessor(UUID documentId, UUID employeeId) {
        jdbcTemplate.update("UPDATE sales_documents SET attach_source_employee_external_id = "
                + "(SELECT external_id FROM employees WHERE id = ?) WHERE id = ?", employeeId, documentId);
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
        return addDocument(
                graph,
                externalId,
                kind,
                businessDate,
                originalDocumentId,
                null
        );
    }

    private UUID addDocument(
            TestGraph graph,
            String externalId,
            String kind,
            LocalDate businessDate,
            UUID originalDocumentId,
            UUID employeeId
    ) {
        UUID documentId = UUID.randomUUID();
        Instant occurredAt = businessDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id,
                    original_document_id, employee_id, document_kind, source_document_type,
                    occurred_at, business_date, net_amount, last_sync_run_id
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?, ?, ?, 'sale', ?, ?, 0, ?)
                """,
                documentId,
                graph.connectionId(),
                externalId,
                graph.storeId(),
                originalDocumentId,
                employeeId,
                kind,
                Timestamp.from(occurredAt),
                businessDate,
                graph.syncRunId()
        );
        return documentId;
    }

    private UUID addEmployee(
            TestGraph graph,
            String name,
            boolean employeeActive,
            boolean assignmentActive,
            boolean participatesInRanking
    ) {
        UUID employeeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO employees (
                    id, connection_id, source_system, external_id, full_name, is_active
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?)
                """,
                employeeId,
                graph.connectionId(),
                "daily-plan-employee-" + employeeId,
                name,
                employeeActive
        );
        jdbcTemplate.update(
                """
                INSERT INTO employee_store_assignments (
                    employee_id, store_id, is_active, participates_in_ranking
                ) VALUES (?, ?, ?, ?)
                """,
                employeeId,
                graph.storeId(),
                assignmentActive,
                participatesInRanking
        );
        return employeeId;
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
