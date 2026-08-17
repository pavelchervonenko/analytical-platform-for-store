package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.service.EmployeeCategoryKpiEmployee;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiEntry;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiGroup;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiResult;
import com.storeanalytics.metrics.service.EmployeeCategoryKpiService;
import com.storeanalytics.metrics.service.EmployeeKpiResult;
import com.storeanalytics.metrics.service.EmployeeKpiService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EmployeeCategoryKpiIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private EmployeeCategoryKpiService employeeCategoryKpiService;

    @Autowired
    private EmployeeKpiService employeeKpiService;

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
        jdbcTemplate.update("DELETE FROM employee_store_assignments");
        jdbcTemplate.update("DELETE FROM employees");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM stores");
    }

    @Test
    void projectsCompleteEmployeeCategoryScopeAndReconcilesEmployeeTotals() {
        TestGraph graph = createGraph();
        UUID assignedId = addEmployee(graph, "assigned", "Assigned");
        UUID zeroId = addEmployee(graph, "zero", "Zero");
        UUID historicalId = addEmployee(graph, "historical", "Historical");
        addAssignment(assignedId, graph.storeId(), true, true);
        addAssignment(zeroId, graph.storeId(), true, true);

        UUID phoneSale = addDocument(
                graph, assignedId, "phone-sale", "SALE", PERIOD_START, "100.00", null
        );
        addItem(graph, new ItemFixture(
                phoneSale, "phone", "IPHONE_NEW_ASIS", "1.000", "100.00", "60.00", "KNOWN"
        ));
        UUID phoneReturn = addDocument(
                graph, assignedId, "phone-return", "RETURN", PERIOD_END, "20.00", phoneSale
        );
        addItem(graph, new ItemFixture(
                phoneReturn, "phone-return-item", "IPHONE_NEW_ASIS",
                "0.200", "20.00", "12.00", "KNOWN"
        ));
        UUID serviceSale = addDocument(
                graph, assignedId, "service-sale", "SALE", PERIOD_START, "30.00", null
        );
        addItem(graph, new ItemFixture(
                serviceSale, "service", "SETUP_SERVICE",
                "1.000", "30.00", null, "MISSING"
        ));
        addSaleWithItem(
                graph, historicalId, "historical-sale", "IPHONE_USED", "40.00", "20.00"
        );
        addSaleWithItem(
                graph, null, "unassigned-sale", "CHARGER_CABLE", "25.00", "10.00"
        );

        EmployeeCategoryKpiResult result =
                employeeCategoryKpiService.calculate(graph.storeId(), period());

        assertThat(result.employees()).hasSize(4);
        EmployeeCategoryKpiEmployee assigned = employee(result, assignedId);
        assertThat(assigned.categories()).hasSize(21);
        assertThat(assigned.netRevenue()).isEqualByComparingTo("110.00");
        assertThat(assigned.rankingEligible()).isTrue();
        assertThat(category(assigned, "IPHONE_NEW_ASIS").metrics().netRevenue())
                .isEqualByComparingTo("80.00");
        assertThat(category(assigned, "IPHONE_NEW_ASIS").metrics().netQuantity())
                .isEqualByComparingTo("0.800");
        assertThat(group(assigned, "PHONES").metrics().netRevenue())
                .isEqualByComparingTo("80.00");
        assertThat(group(assigned, "DEVICES").metrics().netRevenue())
                .isEqualByComparingTo("80.00");
        assertThat(group(assigned, "SERVICE").metrics().netRevenue())
                .isEqualByComparingTo("30.00");
        assertThat(group(assigned, "SERVICE").metrics().costAmount()).isNull();
        assertThat(group(assigned, "ADDITIONAL_REVENUE").metrics().netRevenue())
                .isEqualByComparingTo("30.00");
        assertThat(assigned.dataQuality().completeCostData()).isFalse();

        EmployeeCategoryKpiEmployee zero = employee(result, zeroId);
        assertThat(zero.categories()).hasSize(21);
        assertThat(zero.netRevenue()).isEqualByComparingTo("0.00");
        assertThat(zero.rankingEligible()).isTrue();

        EmployeeCategoryKpiEmployee historical = employee(result, historicalId);
        assertThat(historical.assignedToStore()).isFalse();
        assertThat(historical.rankingEligible()).isFalse();
        assertThat(historical.netRevenue()).isEqualByComparingTo("40.00");

        EmployeeCategoryKpiEmployee unassigned = result.employees().stream()
                .filter(EmployeeCategoryKpiEmployee::unassigned)
                .findFirst()
                .orElseThrow();
        assertThat(unassigned.employeeId()).isNull();
        assertThat(unassigned.netRevenue()).isEqualByComparingTo("25.00");

        BigDecimal projectionRevenue = result.employees().stream()
                .map(EmployeeCategoryKpiEmployee::netRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        EmployeeKpiResult employeeKpi = employeeKpiService.calculate(graph.storeId(), period());
        BigDecimal employeeKpiRevenue = employeeKpi.employees().stream()
                .map(entry -> entry.netRevenue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(projectionRevenue).isEqualByComparingTo(employeeKpiRevenue);
        assertThat(projectionRevenue).isEqualByComparingTo("175.00");
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
                ) VALUES (?, ?, 'LIVESKLAD', 'employee-category-store', 'Employee category store')
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
                ) VALUES (
                    ?, ?, 'LIVESKLAD', 'employee-category-product',
                    'Employee category product', 'PRODUCT'
                )
                """,
                productId,
                connectionId
        );
        return new TestGraph(connectionId, storeId, syncRunId, productId);
    }

    private UUID addEmployee(TestGraph graph, String externalId, String fullName) {
        UUID employeeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO employees (
                    id, connection_id, source_system, external_id, full_name, is_active
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, true)
                """,
                employeeId,
                graph.connectionId(),
                externalId,
                fullName
        );
        return employeeId;
    }

    private void addAssignment(
            UUID employeeId,
            UUID storeId,
            boolean active,
            boolean participates
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO employee_store_assignments (
                    employee_id, store_id, is_active, participates_in_ranking
                ) VALUES (?, ?, ?, ?)
                """,
                employeeId,
                storeId,
                active,
                participates
        );
    }

    private void addSaleWithItem(
            TestGraph graph,
            UUID employeeId,
            String externalId,
            String categoryCode,
            String netAmount,
            String costAmount
    ) {
        UUID documentId = addDocument(
                graph, employeeId, externalId, "SALE", PERIOD_START, netAmount, null
        );
        addItem(graph, new ItemFixture(
                documentId,
                externalId + "-item",
                categoryCode,
                "1.000",
                netAmount,
                costAmount,
                "KNOWN"
        ));
    }

    private UUID addDocument(
            TestGraph graph,
            UUID employeeId,
            String externalId,
            String kind,
            LocalDate businessDate,
            String netAmount,
            UUID originalDocumentId
    ) {
        UUID documentId = UUID.randomUUID();
        Instant occurredAt = businessDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id, employee_id,
                    original_document_id, document_kind, source_document_type, occurred_at,
                    business_date, net_amount, is_deleted, last_sync_run_id
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?, ?, ?, 'sale', ?, ?, ?, false, ?)
                """,
                documentId,
                graph.connectionId(),
                externalId,
                graph.storeId(),
                employeeId,
                originalDocumentId,
                kind,
                Timestamp.from(occurredAt),
                businessDate,
                new BigDecimal(netAmount),
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
                ) VALUES (
                    ?, ?, ?, 'Employee category product', ?, 'NEW', ?, ?, ?, 0, ?, ?, ?, false
                )
                """,
                item.documentId(),
                item.externalId(),
                graph.productId(),
                categoryId,
                new BigDecimal(item.quantity()),
                new BigDecimal(item.netAmount()),
                new BigDecimal(item.netAmount()),
                new BigDecimal(item.netAmount()),
                item.costAmount() == null ? null : new BigDecimal(item.costAmount()),
                item.costQuality()
        );
    }

    private EmployeeCategoryKpiEmployee employee(
            EmployeeCategoryKpiResult result,
            UUID employeeId
    ) {
        return result.employees().stream()
                .filter(entry -> employeeId.equals(entry.employeeId()))
                .findFirst()
                .orElseThrow();
    }

    private EmployeeCategoryKpiEntry category(
            EmployeeCategoryKpiEmployee employee,
            String code
    ) {
        return employee.categories().stream()
                .filter(entry -> entry.categoryCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private EmployeeCategoryKpiGroup group(
            EmployeeCategoryKpiEmployee employee,
            String code
    ) {
        return employee.groups().stream()
                .filter(entry -> entry.groupCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private StoreKpiPeriod period() {
        return new StoreKpiPeriod(PERIOD_START, PERIOD_END);
    }

    private record TestGraph(
            UUID connectionId,
            UUID storeId,
            UUID syncRunId,
            UUID productId
    ) {
    }

    private record ItemFixture(
            UUID documentId,
            String externalId,
            String categoryCode,
            String quantity,
            String netAmount,
            String costAmount,
            String costQuality
    ) {
    }
}
