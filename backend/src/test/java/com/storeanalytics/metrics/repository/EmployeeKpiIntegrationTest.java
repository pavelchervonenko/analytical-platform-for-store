package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.service.EmployeeKpiEntry;
import com.storeanalytics.metrics.service.EmployeeKpiResult;
import com.storeanalytics.metrics.service.EmployeeKpiService;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.metrics.service.StoreKpiService;
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
class EmployeeKpiIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 7, 31);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private EmployeeKpiService employeeKpiService;

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
        jdbcTemplate.update("DELETE FROM employee_store_assignments");
        jdbcTemplate.update("DELETE FROM employees");
        jdbcTemplate.update("DELETE FROM products");
        jdbcTemplate.update("DELETE FROM sync_runs");
        jdbcTemplate.update("DELETE FROM stores");
    }

    @Test
    void returnsCompleteEmployeeScopeWithoutChangingStoreTotals() {
        TestGraph graph = createGraph();
        UUID eligibleId = addEmployee(graph, "eligible", "Eligible", true);
        UUID nonParticipantId = addEmployee(graph, "not-ranked", "Not ranked", true);
        UUID zeroId = addEmployee(graph, "zero", "Zero", true);
        UUID inactiveAssignmentId = addEmployee(
                graph, "inactive-assignment", "Inactive assignment", true
        );
        UUID historicalId = addEmployee(graph, "historical", "Historical", true);

        addAssignment(eligibleId, graph.storeId(), true, true);
        addAssignment(nonParticipantId, graph.storeId(), true, false);
        addAssignment(zeroId, graph.storeId(), true, true);
        addAssignment(inactiveAssignmentId, graph.storeId(), false, true);

        UUID eligibleSale = addDocument(graph, sale(
                graph.storeId(), eligibleId, "eligible-sale", PERIOD_START, "100.00"
        ));
        addItem(graph, knownItem(
                eligibleSale, "eligible-item", "IPHONE_NEW_ASIS", "2.000", "100.00", "60.00"
        ));
        addItem(graph, knownItem(
                eligibleSale, "excluded", "EXCLUDE", "1.000", "999.00", "500.00"
        ));
        addItem(graph, deletedKnownItem(
                eligibleSale, "deleted", "IPHONE_NEW_ASIS", "1.000", "999.00", "500.00"
        ));
        UUID eligibleReturn = addDocument(graph, returnDocument(
                graph.storeId(), eligibleId, "eligible-return", PERIOD_END, "20.00", eligibleSale
        ));
        addItem(graph, knownItem(
                eligibleReturn, "return-item", "IPHONE_NEW_ASIS", "0.500", "20.00", "10.00"
        ));

        addSaleWithItem(graph, nonParticipantId, "not-ranked-sale", "50.00", "30.00");
        addSaleWithItem(graph, inactiveAssignmentId, "inactive-sale", "30.00", "15.00");
        addSaleWithItem(graph, historicalId, "historical-sale", "40.00", "20.00");
        addSaleWithItem(graph, null, "unassigned-sale", "25.00", "10.00");
        addOtherStoreFact(graph, eligibleId);

        EmployeeKpiResult result = employeeKpiService.calculate(graph.storeId(), period());

        assertThat(result.employees()).hasSize(6);
        EmployeeKpiEntry eligible = employee(result, eligibleId);
        assertThat(eligible.netRevenue()).isEqualByComparingTo("80.00");
        assertThat(eligible.netQuantity()).isEqualByComparingTo("1.500");
        assertThat(eligible.costAmount()).isEqualByComparingTo("50.00");
        assertThat(eligible.rankingEligible()).isTrue();

        EmployeeKpiEntry nonParticipant = employee(result, nonParticipantId);
        assertThat(nonParticipant.netRevenue()).isEqualByComparingTo("50.00");
        assertThat(nonParticipant.participatesInRanking()).isFalse();
        assertThat(nonParticipant.rankingEligible()).isFalse();

        EmployeeKpiEntry zero = employee(result, zeroId);
        assertThat(zero.netRevenue()).isEqualByComparingTo("0.00");
        assertThat(zero.dataQuality().includedItemCount()).isZero();
        assertThat(zero.rankingEligible()).isTrue();

        EmployeeKpiEntry inactiveAssignment = employee(result, inactiveAssignmentId);
        assertThat(inactiveAssignment.assignmentActive()).isFalse();
        assertThat(inactiveAssignment.rankingEligible()).isFalse();
        assertThat(inactiveAssignment.netRevenue()).isEqualByComparingTo("30.00");

        EmployeeKpiEntry historical = employee(result, historicalId);
        assertThat(historical.assignedToStore()).isFalse();
        assertThat(historical.netRevenue()).isEqualByComparingTo("40.00");

        EmployeeKpiEntry unassigned = result.employees().stream()
                .filter(EmployeeKpiEntry::unassigned)
                .findFirst()
                .orElseThrow();
        assertThat(unassigned.employeeId()).isNull();
        assertThat(unassigned.displayName()).isEqualTo("Не назначен");
        assertThat(unassigned.netRevenue()).isEqualByComparingTo("25.00");

        BigDecimal employeeRevenue = result.employees().stream()
                .map(EmployeeKpiEntry::netRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        StoreKpiResult storeKpi = storeKpiService.calculate(graph.storeId(), period());
        assertThat(employeeRevenue).isEqualByComparingTo(storeKpi.netRevenue());
        assertThat(storeKpi.netRevenue()).isEqualByComparingTo("225.00");
    }

    @Test
    void disablingParticipationOnlyChangesRankingEligibility() {
        TestGraph graph = createGraph();
        UUID employeeId = addEmployee(graph, "toggle", "Toggle", true);
        addAssignment(employeeId, graph.storeId(), true, true);
        addSaleWithItem(graph, employeeId, "toggle-sale", "100.00", "60.00");

        EmployeeKpiEntry before = employee(
                employeeKpiService.calculate(graph.storeId(), period()),
                employeeId
        );
        jdbcTemplate.update(
                """
                UPDATE employee_store_assignments
                SET participates_in_ranking = false
                WHERE employee_id = ? AND store_id = ?
                """,
                employeeId,
                graph.storeId()
        );
        EmployeeKpiEntry after = employee(
                employeeKpiService.calculate(graph.storeId(), period()),
                employeeId
        );

        assertThat(before.rankingEligible()).isTrue();
        assertThat(after.participatesInRanking()).isFalse();
        assertThat(after.rankingEligible()).isFalse();
        assertThat(after.netRevenue()).isEqualByComparingTo(before.netRevenue());
        assertThat(after.netQuantity()).isEqualByComparingTo(before.netQuantity());
        assertThat(after.costAmount()).isEqualByComparingTo(before.costAmount());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM sales_documents WHERE employee_id = ?",
                Long.class,
                employeeId
        )).isOne();
    }

    @Test
    void exposesMissingCostOnlyForAffectedEmployee() {
        TestGraph graph = createGraph();
        UUID completeId = addEmployee(graph, "complete", "Complete", true);
        UUID incompleteId = addEmployee(graph, "incomplete", "Incomplete", true);
        addAssignment(completeId, graph.storeId(), true, true);
        addAssignment(incompleteId, graph.storeId(), true, true);
        addSaleWithItem(graph, completeId, "complete-sale", "50.00", "20.00");

        UUID documentId = addDocument(graph, sale(
                graph.storeId(), incompleteId, "incomplete-sale", PERIOD_START, "30.00"
        ));
        addItem(graph, missingCostItem(
                documentId, "missing", "UNMAPPED", "1.000", "30.00"
        ));

        EmployeeKpiResult result = employeeKpiService.calculate(graph.storeId(), period());

        assertThat(employee(result, completeId).costAmount()).isEqualByComparingTo("20.00");
        EmployeeKpiEntry incomplete = employee(result, incompleteId);
        assertThat(incomplete.costAmount()).isNull();
        assertThat(incomplete.grossProfit()).isNull();
        assertThat(incomplete.marginPercent()).isNull();
        assertThat(incomplete.dataQuality().missingCostItemCount()).isOne();
        assertThat(incomplete.dataQuality().unmappedItemCount()).isOne();
    }

    private TestGraph createGraph() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        UUID otherStoreId = UUID.randomUUID();
        addStore(storeId, connectionId, "employee-kpi-store");
        addStore(otherStoreId, connectionId, "employee-kpi-other-store");

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
                ) VALUES (?, ?, 'LIVESKLAD', 'employee-kpi-product', 'KPI product', 'PRODUCT')
                """,
                productId,
                connectionId
        );
        return new TestGraph(connectionId, storeId, otherStoreId, syncRunId, productId);
    }

    private UUID addEmployee(
            TestGraph graph,
            String externalId,
            String fullName,
            boolean active
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
                externalId,
                fullName,
                active
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
            String netAmount,
            String costAmount
    ) {
        UUID documentId = addDocument(graph, sale(
                graph.storeId(), employeeId, externalId, PERIOD_START, netAmount
        ));
        addItem(graph, knownItem(
                documentId,
                externalId + "-item",
                "IPHONE_NEW_ASIS",
                "1.000",
                netAmount,
                costAmount
        ));
    }

    private void addOtherStoreFact(TestGraph graph, UUID employeeId) {
        UUID documentId = addDocument(graph, sale(
                graph.otherStoreId(), employeeId, "other-store-sale", PERIOD_START, "999.00"
        ));
        addItem(graph, knownItem(
                documentId,
                "other-store-item",
                "IPHONE_NEW_ASIS",
                "1.000",
                "999.00",
                "500.00"
        ));
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
        Instant occurredAt = document.businessDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        jdbcTemplate.update(
                """
                INSERT INTO sales_documents (
                    id, connection_id, source_system, external_id, store_id, employee_id,
                    original_document_id, document_kind, source_document_type, occurred_at,
                    business_date, net_amount, is_deleted, last_sync_run_id
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, ?, ?, ?, 'sale', ?, ?, ?, ?, ?)
                """,
                documentId,
                graph.connectionId(),
                document.externalId(),
                document.storeId(),
                document.employeeId(),
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

    private DocumentFixture sale(
            UUID storeId,
            UUID employeeId,
            String externalId,
            LocalDate businessDate,
            String netAmount
    ) {
        return document(
                storeId, employeeId, externalId, "SALE", businessDate, netAmount, null
        );
    }

    private DocumentFixture returnDocument(
            UUID storeId,
            UUID employeeId,
            String externalId,
            LocalDate businessDate,
            String netAmount,
            UUID originalDocumentId
    ) {
        return document(
                storeId,
                employeeId,
                externalId,
                "RETURN",
                businessDate,
                netAmount,
                originalDocumentId
        );
    }

    private DocumentFixture document(
            UUID storeId,
            UUID employeeId,
            String externalId,
            String kind,
            LocalDate businessDate,
            String netAmount,
            UUID originalDocumentId
    ) {
        return new DocumentFixture(
                storeId,
                employeeId,
                externalId,
                kind,
                businessDate,
                new BigDecimal(netAmount),
                false,
                originalDocumentId
        );
    }

    private ItemFixture knownItem(
            UUID documentId,
            String externalId,
            String categoryCode,
            String quantity,
            String netAmount,
            String costAmount
    ) {
        return item(
                documentId, externalId, categoryCode, quantity, netAmount,
                new ItemCost(costAmount, "KNOWN", false)
        );
    }

    private ItemFixture deletedKnownItem(
            UUID documentId,
            String externalId,
            String categoryCode,
            String quantity,
            String netAmount,
            String costAmount
    ) {
        return item(
                documentId, externalId, categoryCode, quantity, netAmount,
                new ItemCost(costAmount, "KNOWN", true)
        );
    }

    private ItemFixture missingCostItem(
            UUID documentId,
            String externalId,
            String categoryCode,
            String quantity,
            String netAmount
    ) {
        return item(
                documentId, externalId, categoryCode, quantity, netAmount,
                new ItemCost(null, "MISSING", false)
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

    private EmployeeKpiEntry employee(EmployeeKpiResult result, UUID employeeId) {
        return result.employees().stream()
                .filter(entry -> employeeId.equals(entry.employeeId()))
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
            UUID employeeId,
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
