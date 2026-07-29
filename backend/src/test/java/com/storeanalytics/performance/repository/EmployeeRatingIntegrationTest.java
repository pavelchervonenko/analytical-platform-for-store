package com.storeanalytics.performance.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.performance.service.EmployeeRatingEntry;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.performance.service.EmployeeRatingService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EmployeeRatingIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private EmployeeRatingService ratingService;

    @Autowired
    private com.storeanalytics.performance.service.EmployeeRatingQueryService ratingQueryService;

    @Autowired
    private com.storeanalytics.performance.service.EmployeeRatingFinalizationService finalizationService;

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
        jdbcTemplate.update("DELETE FROM employee_rating_snapshots");
        jdbcTemplate.update("DELETE FROM employee_work_shifts");
        jdbcTemplate.update("DELETE FROM store_performance_plans");
        jdbcTemplate.update("DELETE FROM sales_document_items");
        jdbcTemplate.update("DELETE FROM sales_documents");
        jdbcTemplate.update("DELETE FROM employee_store_assignments");
        jdbcTemplate.update("DELETE FROM employees");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM stores");
        jdbcTemplate.update("DELETE FROM app_users WHERE email LIKE 'rating-actor-%'");
    }

    @Test
    void calculatesRatingFromPersistedSalesPlansShiftsAndEmployeeAttachRates() {
        TestGraph graph = createGraph();
        UUID firstEmployee = addEmployee(graph, "first", "First");
        UUID secondEmployee = addEmployee(graph, "second", "Second");
        addAssignment(firstEmployee, graph.storeId());
        addAssignment(secondEmployee, graph.storeId());
        addShift(graph.storeId(), firstEmployee, LocalDate.of(2026, 7, 1));
        addShift(graph.storeId(), firstEmployee, LocalDate.of(2026, 7, 2), "5.00");
        addShift(graph.storeId(), secondEmployee, LocalDate.of(2026, 7, 1));
        addPlan(graph.storeId());

        UUID firstSale = addSale(graph, firstEmployee, "first-sale", "540.00");
        addItem(graph, firstSale, "first-phone", "IPHONE_NEW_ASIS", "3.000", "500.00");
        addItem(graph, firstSale, "first-case", "CASE_APPLE_IPHONE", "3.000", "25.00");
        addItem(graph, firstSale, "first-service", "SETUP_SERVICE", "3.000", "15.00");
        UUID secondSale = addSale(graph, secondEmployee, "second-sale", "600.00");
        addItem(graph, secondSale, "second-phone", "IPHONE_NEW_ASIS", "3.000", "600.00");

        EmployeeRatingResult result = ratingService.calculate(
                graph.storeId(), new StoreKpiPeriod(PERIOD_START, PERIOD_END)
        );

        assertThat(result.plan().complete()).isTrue();
        assertThat(result.plan().actualStoreRevenue()).isEqualByComparingTo("1140.00");
        assertThat(result.employees()).hasSize(2);
        EmployeeRatingEntry first = result.employees().getFirst();
        assertThat(first.employeeId()).isEqualTo(firstEmployee);
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.shiftCount()).isEqualTo(2);
        assertThat(first.workedHours()).isEqualByComparingTo("16.00");
        assertThat(first.accessorySharePercent()).isEqualByComparingTo("4.63");
        assertThat(first.serviceSharePercent()).isEqualByComparingTo("2.78");
        assertThat(first.scores().attachScore()).isEqualByComparingTo("150.00");
        assertThat(first.attachRates()).hasSize(12);
        assertThat(first.attachRates()).anySatisfy(rate -> {
            assertThat(rate.metricCode()).isEqualTo("CASE_APPLE_IPHONE");
            assertThat(rate.denominatorQuantity()).isEqualByComparingTo("3.000");
            assertThat(rate.ratePercent()).isEqualByComparingTo("100.0");
            assertThat(rate.includedInScore()).isTrue();
        });

        EmployeeRatingEntry second = result.employees().get(1);
        assertThat(second.employeeId()).isEqualTo(secondEmployee);
        assertThat(second.rank()).isEqualTo(2);
        assertThat(second.scores().attachScore()).isEqualByComparingTo("0.00");
    }

    @Test
    void finalizedRatingRemainsUnchangedAfterLateSourceCorrections() {
        StoreKpiPeriod closedPeriod = new StoreKpiPeriod(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 20)
        );
        TestGraph graph = createGraph();
        UUID actorId = addActor();
        UUID employeeId = addEmployee(graph, "history-employee", "History Employee");
        addAssignment(employeeId, graph.storeId());
        addShift(graph.storeId(), employeeId, LocalDate.of(2026, 7, 1));
        addPlan(graph.storeId());
        UUID initialSale = addSale(
                graph, employeeId, "history-initial", "100.00"
        );
        addItem(
                graph, initialSale, "history-initial-item",
                "IPHONE_NEW_ASIS", "1.000", "100.00"
        );

        EmployeeRatingResult live = ratingQueryService.get(graph.storeId(), closedPeriod);
        EmployeeRatingResult finalized = finalizationService.finalizePeriod(
                graph.storeId(), closedPeriod, actorId
        );

        UUID lateSale = addSale(graph, employeeId, "history-late", "900.00");
        addItem(
                graph, lateSale, "history-late-item",
                "IPHONE_NEW_ASIS", "1.000", "900.00"
        );
        jdbcTemplate.update(
                "UPDATE employee_work_shifts SET worked_hours = 5.00 "
                        + "WHERE store_id = ? AND employee_id = ?",
                graph.storeId(), employeeId
        );

        EmployeeRatingResult recalculated = ratingService.calculate(
                graph.storeId(), closedPeriod
        );
        EmployeeRatingResult historical = ratingQueryService.get(
                graph.storeId(), closedPeriod
        );
        EmployeeRatingResult repeated = finalizationService.finalizePeriod(
                graph.storeId(), closedPeriod, actorId
        );

        assertThat(live.history().status().name()).isEqualTo("LIVE");
        assertThat(finalized.history().status().name()).isEqualTo("FINALIZED");
        assertThat(finalized.employees().getFirst().netRevenue())
                .isEqualByComparingTo("100.00");
        assertThat(recalculated.employees().getFirst().netRevenue())
                .isEqualByComparingTo("1000.00");
        assertThat(recalculated.employees().getFirst().workedHours())
                .isEqualByComparingTo("5.00");
        assertThat(historical.employees()).isEqualTo(finalized.employees());
        assertThat(historical.history()).isEqualTo(finalized.history());
        assertThat(repeated).isEqualTo(finalized);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM employee_rating_snapshots WHERE store_id = ?",
                Long.class,
                graph.storeId()
        )).isEqualTo(1L);
    }

    private UUID addActor() {
        UUID actorId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO app_users (
                    id, email, password_hash, display_name, role, is_active,
                    password_change_required, security_version
                ) VALUES (?, ?, '{noop}unused', 'Rating Manager', 'ADMIN', true, false, 0)
                """,
                actorId,
                "rating-actor-" + actorId + "@example.invalid"
        );
        return actorId;
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
                ) VALUES (?, ?, 'LIVESKLAD', 'rating-store', 'Rating store')
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
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    id, connection_id, source_system, external_id, name, source_kind
                ) VALUES (?, ?, 'LIVESKLAD', 'rating-product', 'Rating product', 'PRODUCT')
                """,
                productId,
                connectionId
        );
        return new TestGraph(connectionId, storeId, syncRunId, productId);
    }

    private UUID addEmployee(TestGraph graph, String externalId, String name) {
        UUID employeeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO employees (
                    id, connection_id, source_system, external_id, full_name
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?)
                """,
                employeeId,
                graph.connectionId(),
                externalId,
                name
        );
        return employeeId;
    }

    private void addAssignment(UUID employeeId, UUID storeId) {
        jdbcTemplate.update(
                """
                INSERT INTO employee_store_assignments (
                    employee_id, store_id, is_active, participates_in_ranking
                ) VALUES (?, ?, true, true)
                """,
                employeeId,
                storeId
        );
    }

    private void addShift(UUID storeId, UUID employeeId, LocalDate workDate) {
        addShift(storeId, employeeId, workDate, "11.00");
    }

    private void addShift(
            UUID storeId,
            UUID employeeId,
            LocalDate workDate,
            String workedHours
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO employee_work_shifts (
                    store_id, employee_id, work_date, worked_hours
                ) VALUES (?, ?, ?, ?)
                """,
                storeId,
                employeeId,
                workDate,
                new BigDecimal(workedHours)
        );
    }

    private void addPlan(UUID storeId) {
        jdbcTemplate.update(
                """
                INSERT INTO store_performance_plans (
                    store_id, plan_month, revenue_target, accessory_share_target,
                    service_share_target, additional_share_target
                ) VALUES (?, DATE '2026-07-01', 1000.00, 4.00, 3.00, 7.00)
                """,
                storeId
        );
    }

    private UUID addSale(
            TestGraph graph,
            UUID employeeId,
            String externalId,
            String netAmount
    ) {
        UUID documentId = UUID.randomUUID();
        Instant occurredAt = PERIOD_START.atStartOfDay(ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id, employee_id,
                    document_kind, source_document_type, occurred_at, business_date,
                    net_amount, last_sync_run_id
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?, 'SALE', 'sale', ?, ?, ?, ?)
                """,
                documentId,
                graph.connectionId(),
                externalId,
                graph.storeId(),
                employeeId,
                Timestamp.from(occurredAt),
                PERIOD_START,
                new BigDecimal(netAmount),
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
            String netAmount
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
                    gross_amount, discount_amount, net_amount, cost_amount, cost_quality
                ) VALUES (?, ?, ?, 'Rating product', ?, 'NEW', ?, ?, ?, 0, ?, 0, 'KNOWN')
                """,
                documentId,
                externalId,
                graph.productId(),
                categoryId,
                new BigDecimal(quantity),
                amount,
                amount,
                amount
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
