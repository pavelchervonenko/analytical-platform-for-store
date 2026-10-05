package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.metrics.repository.EmployeeCategoryKpiRepository;
import com.storeanalytics.metrics.repository.EmployeeKpiRepository;
import com.storeanalytics.metrics.repository.SellerCohortRepository;
import com.storeanalytics.metrics.repository.SellerDocumentRepository;
import com.storeanalytics.metrics.repository.SellerHistoricalDocumentSelectionRepository;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SellerHistoricalFinancialFactsServiceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static final StoreKpiPeriod PREVIOUS = new StoreKpiPeriod(
            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13));
    private static final StoreKpiPeriod CURRENT = new StoreKpiPeriod(
            LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 20));
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static SellerHistoricalFinancialFactsService service;

    @BeforeAll
    static void initialize() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        var named = new NamedParameterJdbcTemplate(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        service = new SellerHistoricalFinancialFactsService(new SellerMembershipHistoryRepository(jdbc),
                new SellerHistoricalDocumentSelectionRepository(named), new SellerCohortRepository(named),
                new EmployeeKpiRepository(named), new EmployeeCategoryKpiRepository(named),
                new SellerDocumentRepository(named));
    }

    @Test
    void membershipAtEachOperationPreservesDepartedSellerAndReturnProcessorWithoutPayrollMutation() {
        Graph graph = seed("2026-09-07T00:00:00Z");
        UUID former = employee(graph, true, "2026-09-07T00:00:00Z", "2026-09-16T00:00:00Z");
        UUID current = employee(graph, false, "2026-09-14T00:00:00Z", null);
        item(graph, former, null, "SALE", "2026-09-08T12:00:00Z", "100", false);
        item(graph, former, null, "SALE", "2026-09-15T12:00:00Z", "80", false);
        item(graph, former, null, "SALE", "2026-09-16T00:00:00Z", "900", false);
        item(graph, current, null, "SALE", "2026-09-17T12:00:00Z", "50", false);
        UUID returned = item(graph, former, current, "RETURN", "2026-09-18T12:00:00Z", "20", false);
        item(graph, current, null, "SALE", "2026-09-19T12:00:00Z", "999", true);

        var result = read(graph, NOW);
        assertThat(result.current().metrics().totals().netRevenue()).isEqualByComparingTo("110");
        assertThat(result.previous().metrics().totals().netRevenue()).isEqualByComparingTo("100");
        assertThat(result.current().metrics().totals().costAmount()).isEqualByComparingTo("55");
        assertThat(result.current().metrics().cohort()).isEqualTo(result.previous().metrics().cohort());
        assertThat(result.current().metrics().employees()).hasSize(2);
        assertThat(result.current().metrics().employees()).filteredOn(row -> row.employeeId().equals(former))
                .singleElement().satisfies(row -> assertThat(row.netRevenue()).isEqualByComparingTo("80"));
        assertThat(result.previous().metrics().employees()).filteredOn(row -> row.employeeId().equals(current))
                .singleElement().satisfies(row -> assertThat(row.netRevenue()).isZero());
        assertThat(result.currentActionEmployeeIds()).containsExactly(current);
        assertThat(jdbc.queryForObject("SELECT employee_id FROM sales_documents WHERE id = ?", UUID.class, returned))
                .isEqualTo(former);
    }

    @Test
    void emptyPeriodsRetainHistoricalMemberEvenWhenAssignmentWasRemoved() {
        Graph graph = seed("2026-09-07T00:00:00Z");
        UUID former = employee(graph, true, "2026-09-07T00:00:00Z", "2026-09-14T00:00:00Z");
        var result = read(graph, NOW);
        assertThat(result.current().metrics().employees()).hasSize(1);
        assertThat(result.current().metrics().employees().getFirst().employeeId()).isEqualTo(former);
        assertThat(result.current().metrics().totals().netRevenue()).isZero();
        assertThat(result.previous().metrics().totals().netRevenue()).isZero();
        assertThat(result.current().documents()).isEmpty();
        assertThat(result.currentActionEmployeeIds()).isEmpty();
    }

    @Test
    void emptyHistoricalCohortDoesNotFallBackToStoreTotals() {
        Graph graph = seed("2026-09-07T00:00:00Z");
        var result = read(graph, NOW);
        assertThat(result.current().metrics().cohort().employeeIds()).isEmpty();
        assertThat(result.current().metrics().totals().netRevenue()).isZero();
    }

    @Test
    void missingAndLateBaselinesCannotBecomeAnEmptySuccessfulHistoricalReport() {
        Graph missing = seed(null);
        assertThatThrownBy(() -> read(missing, NOW)).hasMessage("MEMBERSHIP_BASELINE_MISSING");
        Graph late = seed("2026-09-07T00:00:00.000001Z");
        assertThatThrownBy(() -> read(late, NOW)).hasMessage("MEMBERSHIP_BASELINE_DOES_NOT_COVER_COMPARISON");
    }

    @Test
    void unknownReturnAuthorAndMissingIntervalFailClosedInsteadOfDroppingMoney() {
        Graph graph = seed("2026-09-07T00:00:00Z");
        UUID current = employee(graph, false, "2026-09-14T00:00:00Z", null);
        item(graph, current, null, "RETURN", "2026-09-18T12:00:00Z", "20", false);
        assertThatThrownBy(() -> read(graph, NOW)).hasMessage("DOCUMENT_MEMBERSHIP_OR_AUTHOR_UNKNOWN");
        Graph gap = seed("2026-09-07T00:00:00Z");
        UUID later = employee(gap, false, "2026-09-14T00:00:00Z", null);
        item(gap, later, null, "SALE", "2026-09-13T12:00:00Z", "20", false);
        assertThatThrownBy(() -> read(gap, NOW)).hasMessage("DOCUMENT_MEMBERSHIP_OR_AUTHOR_UNKNOWN");
    }

    @Test
    void openOrNonweeklyPeriodsAreNotHistoricalPreparationApproval() {
        Graph graph = seed("2026-09-07T00:00:00Z");
        assertThatThrownBy(() -> read(graph, NOW.minusNanos(1))).hasMessage("PERIOD_NOT_CLOSED");
        assertThatThrownBy(() -> transaction.execute(status -> service.read(graph.store(),
                new StoreKpiPeriod(CURRENT.start(), CURRENT.end().minusDays(1)), PREVIOUS, ZoneOffset.UTC, NOW)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("calendar weeks");
    }

    @Test
    void closureAndBaselineUseLocalMidnightNotUtcDate() {
        Graph graph = seed("2026-09-06T22:00:00Z");
        var zone = java.time.ZoneId.of("Europe/Kaliningrad");
        Instant closure = Instant.parse("2026-09-20T22:00:00Z");
        assertThatThrownBy(() -> transaction.execute(status -> service.read(graph.store(), CURRENT, PREVIOUS,
                zone, closure.minusNanos(1)))).hasMessage("PERIOD_NOT_CLOSED");
        SellerHistoricalFinancialComparison closed = transaction.execute(status ->
                service.read(graph.store(), CURRENT, PREVIOUS, zone, closure));
        assertThat(closed).isNotNull();
    }

    @Test
    void unchangedHistoricalMembershipMatchesCurrentRosterFinancialFormulas() {
        Graph graph = seed("2026-09-07T00:00:00Z");
        UUID first = employee(graph, false, "2026-09-07T00:00:00Z", null);
        UUID second = employee(graph, false, "2026-09-07T00:00:00Z", null);
        item(graph, first, null, "SALE", "2026-09-08T12:00:00Z", "100", false);
        item(graph, first, null, "SALE", "2026-09-15T12:00:00Z", "80", false);
        item(graph, second, null, "SALE", "2026-09-17T12:00:00Z", "50", false);
        item(graph, first, second, "RETURN", "2026-09-18T12:00:00Z", "20", false);
        transaction.executeWithoutResult(status -> {
            var temporal = service.read(graph.store(), CURRENT, PREVIOUS, ZoneOffset.UTC, NOW);
            var named = new NamedParameterJdbcTemplate(jdbc);
            SellerCohortSnapshot cohort = new SellerCohortRepository(named).read(graph.store());
            var financial = new EmployeeKpiRepository(named);
            var category = new EmployeeCategoryKpiRepository(named);
            var document = new SellerDocumentRepository(named);
            for (var period : java.util.List.of(CURRENT, PREVIOUS)) {
                var expected = SellerPeriodMetricCalculator.calculate(cohort, period,
                        financial.aggregate(graph.store(), period.start(), period.end()),
                        category.aggregate(graph.store(), period.start(), period.end()));
                var actual = period.equals(CURRENT) ? temporal.current() : temporal.previous();
                assertThat(actual.metrics()).isEqualTo(expected);
                assertThat(actual.documents()).isEqualTo(document.read(cohort, period));
            }
        });
    }

    private SellerHistoricalFinancialComparison read(Graph graph, Instant now) {
        return transaction.execute(status -> service.read(graph.store(), CURRENT, PREVIOUS, ZoneOffset.UTC, now));
    }

    private Graph seed(String baseline) {
        UUID connection = jdbc.queryForObject("SELECT id FROM integration_connections "
                + "WHERE connection_key = 'livesklad-default'", UUID.class);
        Graph graph = new Graph(UUID.randomUUID(), connection, UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO stores(id,connection_id,source_system,external_id,name) "
                + "VALUES (?,?,'LIVESKLAD',?,'Synthetic store')",
                graph.store(), connection, graph.store().toString());
        jdbc.update("INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,status) "
                + "VALUES (?,?,'LIVESKLAD','MANUAL','SALES','RUNNING')", graph.run(), connection);
        jdbc.update("INSERT INTO products(id,connection_id,source_system,external_id,name,source_kind) "
                + "VALUES (?,?,'LIVESKLAD',?,'Synthetic service','SERVICE')",
                graph.product(), connection, graph.product().toString());
        if (baseline != null) {
            jdbc.update("INSERT INTO store_seller_membership_state(store_id,authoritative_from,baseline_source) "
                    + "VALUES (?,?::timestamptz,'SYNTHETIC_ONLY')", graph.store(), baseline);
        }
        return graph;
    }

    private UUID employee(Graph graph, boolean departed, String from, String through) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO employees(id,connection_id,source_system,external_id,full_name,is_active) "
                + "VALUES (?,?,'LIVESKLAD',?,'Synthetic employee',?)",
                id, graph.connection(), id.toString(), !departed);
        if (!departed) {
            jdbc.update("INSERT INTO employee_store_assignments"
                    + "(employee_id,store_id,is_active,participates_in_ranking) "
                    + "VALUES (?,?,true,true)", id, graph.store());
        }
        jdbc.update("INSERT INTO seller_membership_history(store_id,employee_id,employee_active,assignment_active,"
                + "participates_in_ranking,valid_from,valid_to,change_source,effective_time_source) "
                + "VALUES (?,?,true,true,true,?::timestamptz,?::timestamptz,'BASELINE','APPROVED_BASELINE')",
                graph.store(), id, from, through);
        if (through != null) {
            jdbc.update("INSERT INTO seller_membership_history(store_id,employee_id,employee_active,assignment_active,"
                    + "participates_in_ranking,valid_from,change_source,effective_time_source) "
                    + "VALUES (?,?,false,false,false,?::timestamptz,'MANUAL','OBSERVED')", graph.store(), id, through);
        }
        return id;
    }

    private UUID item(Graph graph, UUID storedEmployee, UUID returnProcessor, String kind,
            String occurred, String amount, boolean deleted) {
        UUID document = UUID.randomUUID();
        Instant instant = Instant.parse(occurred);
        jdbc.update("INSERT INTO sales_documents(id,connection_id,external_id,store_id,employee_id,document_kind,"
                + "source_document_type,occurred_at,business_date,net_amount,last_sync_run_id,"
                + "attach_source_employee_external_id,is_deleted) VALUES (?,?,?,?,?,?,'sale',?,?,?::numeric,?,?,?)",
                document, graph.connection(), document.toString(), graph.store(), storedEmployee, kind,
                Timestamp.from(instant), LocalDate.ofInstant(instant, ZoneOffset.UTC), amount, graph.run(),
                returnProcessor == null ? null : returnProcessor.toString(), deleted);
        jdbc.update("INSERT INTO sales_document_items(sales_document_id,external_id,product_id,product_name_snapshot,"
                + "analytics_category_id,condition_type_snapshot,quantity,unit_price,gross_amount,discount_amount,"
                + "net_amount,cost_amount,cost_quality) SELECT ?,?,?,'Synthetic service',id,'UNKNOWN',1,"
                + "?::numeric,?::numeric,0,?::numeric,?::numeric/2,'KNOWN' "
                + "FROM analytics_categories WHERE code='SETUP_SERVICE'",
                document, UUID.randomUUID().toString(), graph.product(), amount, amount, amount, amount);
        return document;
    }

    private record Graph(UUID store, UUID connection, UUID run, UUID product) { }
}
