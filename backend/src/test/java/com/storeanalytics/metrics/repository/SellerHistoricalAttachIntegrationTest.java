package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.StoreKpiPeriod;
import com.storeanalytics.metrics.warranty.AttachAttributionPolicy;
import java.time.LocalDate;
import java.util.List;
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
class SellerHistoricalAttachIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static final StoreKpiPeriod PERIOD = new StoreKpiPeriod(
            LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-20"));
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static SellerAttachRateRepository reader;

    @BeforeAll
    static void initialize() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        var named = new NamedParameterJdbcTemplate(jdbc);
        reader = new SellerAttachRateRepository(named, new AttachAttributionPolicy(true),
                new AttachAttributionQualityRepository(named));
    }

    @Test
    void ordinaryRowsUseOwnProcessorAndExactMembershipBoundaryWithoutCurrentRosterFiltering() {
        Graph graph = graph();
        item(graph, graph.former(), false, "2026-09-15T12:00:00Z", "FILM_PHONE", "3", null);
        item(graph, graph.former(), false, "2026-09-16T00:00:00Z", "FILM_PHONE", "9", null);
        Item returned = item(graph, graph.former(), true, "2026-09-18T12:00:00Z", "FILM_PHONE", "1", null);
        jdbc.update("UPDATE sales_documents SET attach_source_employee_external_id = ? WHERE id = ?",
                graph.current().toString(), returned.document());
        var rates = read(graph);
        assertQuantity(rates, "FILM_PHONE", "2", "0");
        assertThat(jdbc.queryForObject("SELECT employee_id FROM sales_documents WHERE id = ?", UUID.class,
                returned.document())).isEqualTo(graph.former());
        assertThat(jdbc.queryForObject("SELECT membership_document_id FROM seller_attach_item_facts_v1 "
                + "WHERE source_item_id = ? AND membership_basis = 'ORDINARY_OPERATION'", UUID.class, returned.id()))
                .isEqualTo(returned.document());
        assertLegacyParity(graph);
    }

    @Test
    void warrantyReturnsKeepTargetSaleAuthorDateAndMembershipInsteadOfProcessor() {
        Graph graph = graph();
        Item device = item(graph, graph.former(), false, "2026-09-15T12:00:00Z", "IPHONE_NEW_ASIS", "2", null);
        Item warranty = item(graph, graph.former(), false, "2026-09-15T12:00:00Z", "WARRANTY_GENERIC", "1", null);
        jdbc.update("UPDATE sales_document_items SET sales_document_id = ? WHERE id = ?",
                device.document(), warranty.id());
        warranty = new Item(warranty.id(), device.document(), warranty.product());
        Item returned = item(graph, graph.former(), true, "2026-09-18T12:00:00Z", "WARRANTY_GENERIC", "0.5", warranty);
        jdbc.update("UPDATE sales_documents SET attach_source_employee_external_id = ? WHERE id = ?",
                graph.current().toString(), returned.document());
        Item returnedDevice = item(graph, graph.former(), true,
                "2026-09-19T12:00:00Z", "IPHONE_NEW_ASIS", "1", device);
        jdbc.update("UPDATE sales_documents SET attach_source_employee_external_id = ? WHERE id = ?",
                graph.current().toString(), returnedDevice.document());
        assertQuantity(read(graph), "WARRANTY_GENERIC_NEW", "0.5", "1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller_attach_item_facts_v1 "
                + "WHERE store_id = ? AND membership_basis = 'WARRANTY_TARGET_SALE' AND employee_id = ? "
                + "AND membership_document_id = ?", Long.class, graph.store(), graph.former(), device.document()))
                .isGreaterThanOrEqualTo(2L);
        jdbc.update("UPDATE sales_documents SET attach_source_employee_external_id = ? WHERE id IN (?,?)",
                graph.former().toString(), returned.document(), returnedDevice.document());
        // The processor no longer participates at return time; the special target-sale base still does.
        assertQuantity(read(graph), "WARRANTY_GENERIC_NEW", "0.5", "1");
        assertLegacyParity(graph);
    }

    @Test
    void unknownHistoryOrProcessorCannotDisappearFromHistoricalAttach() {
        Graph graph = graph();
        item(graph, graph.current(), true, "2026-09-18T12:00:00Z", "FILM_PHONE", "1", null);
        assertThatThrownBy(() -> read(graph)).hasMessage("ATTACH_MEMBERSHIP_OR_AUTHOR_UNKNOWN");
        Graph noHistory = graph();
        UUID stranger = UUID.randomUUID();
        jdbc.update("INSERT INTO employees(id,connection_id,source_system,external_id,full_name) "
                + "VALUES (?,?,'LIVESKLAD',?,'Synthetic unknown')",
                stranger, noHistory.connection(), stranger.toString());
        item(noHistory, stranger, false, "2026-09-18T12:00:00Z", "FILM_PHONE", "1", null);
        assertThatThrownBy(() -> read(noHistory)).hasMessage("ATTACH_MEMBERSHIP_OR_AUTHOR_UNKNOWN");
    }

    @Test
    void emptyOrIncompleteFinancialCohortCannotSilentlyDropAnEligibleAttachFact() {
        Graph graph = graph();
        item(graph, graph.former(), false, "2026-09-15T12:00:00Z", "FILM_PHONE", "1", null);
        assertThatThrownBy(() -> transaction.execute(status -> reader.readHistorical(
                new SellerCohortSnapshot(graph.store(), List.of(graph.current())), PERIOD)))
                .hasMessage("ATTACH_COHORT_NOT_COVERED");
        assertThatThrownBy(() -> transaction.execute(status -> reader.readHistorical(
                new SellerCohortSnapshot(graph.store(), List.of()), PERIOD))).hasMessage("ATTACH_COHORT_NOT_COVERED");
    }

    @Test
    void currentV4ParityHoldsForAnUnchangedCohortAndHistoricalReadRequiresConsistentTransaction() {
        Graph graph = graph();
        item(graph, graph.current(), false, "2026-09-18T12:00:00Z", "FILM_PHONE", "2", null);
        item(graph, graph.current(), false, "2026-09-18T12:00:00Z", "IPHONE_NEW_ASIS", "1", null);
        SellerCohortSnapshot cohort = cohort(graph);
        var temporal = read(graph);
        var current = transaction.execute(status -> reader.read(cohort, PERIOD));
        assertThat(temporal).isEqualTo(current);
        assertThatThrownBy(() -> reader.readHistorical(cohort, PERIOD)).isInstanceOf(IllegalStateException.class);
        var named = new NamedParameterJdbcTemplate(jdbc);
        var disabled = new SellerAttachRateRepository(named, new AttachAttributionPolicy(false),
                new AttachAttributionQualityRepository(named));
        assertThatThrownBy(() -> transaction.execute(status -> disabled.readHistorical(cohort, PERIOD)))
                .hasMessage("HISTORICAL_ATTACH_REQUIRES_V4_POLICY");
        assertLegacyParity(graph);
    }

    private List<AttachRateAggregate> read(Graph graph) {
        return transaction.execute(status -> reader.readHistorical(cohort(graph), PERIOD));
    }

    private SellerCohortSnapshot cohort(Graph graph) {
        return new SellerCohortSnapshot(graph.store(), List.of(graph.former(), graph.current()));
    }

    private void assertQuantity(List<AttachRateAggregate> rates, String metric, String numerator, String denominator) {
        assertThat(rates).filteredOn(row -> row.metricCode().equals(metric)).singleElement().satisfies(row -> {
            assertThat(row.numeratorReceiptCount()).isEqualByComparingTo(numerator);
            assertThat(row.denominatorReceiptCount()).isEqualByComparingTo(denominator);
        });
    }

    private void assertLegacyParity(Graph graph) {
        String columns = "store_id,business_date,employee_id,net_quantity,numerator_metric_code,device_role,"
                + "denominator_metric_codes,classification_issue_code,numerator_metric_codes";
        String original = "SELECT " + columns + " FROM attach_rate_item_facts_v4_catalog WHERE store_id = ?";
        String temporal = "SELECT " + columns + " FROM seller_attach_item_facts_v1 WHERE store_id = ?";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ((" + original + " EXCEPT ALL " + temporal
                + ") UNION ALL (" + temporal + " EXCEPT ALL " + original + ")) delta", Long.class,
                graph.store(), graph.store(), graph.store(), graph.store())).isZero();
    }

    private Graph graph() {
        UUID connection = jdbc.queryForObject("SELECT id FROM integration_connections "
                + "WHERE connection_key = 'livesklad-default'", UUID.class);
        Graph graph = new Graph(UUID.randomUUID(), connection, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO stores(id,connection_id,source_system,external_id,name) "
                + "VALUES (?,?,'LIVESKLAD',?,'Synthetic store')", graph.store(), connection, graph.store().toString());
        jdbc.update("INSERT INTO sync_runs(id,connection_id,source_system,trigger_type,sync_scope,status) "
                + "VALUES (?,?,'LIVESKLAD','MANUAL','SALES','RUNNING')", graph.run(), connection);
        for (UUID employee : List.of(graph.former(), graph.current())) {
            jdbc.update("INSERT INTO employees(id,connection_id,source_system,external_id,full_name,is_active) "
                    + "VALUES (?,?,'LIVESKLAD',?,'Synthetic employee',?)", employee, connection,
                    employee.toString(), employee.equals(graph.current()));
        }
        jdbc.update("INSERT INTO store_seller_membership_state(store_id,authoritative_from,baseline_source) "
                + "VALUES (?,'2026-09-07Z','SYNTHETIC_ONLY')", graph.store());
        jdbc.update("INSERT INTO seller_membership_history(store_id,employee_id,employee_active,assignment_active,"
                + "participates_in_ranking,valid_from,valid_to,change_source,effective_time_source) "
                + "VALUES (?,?,true,true,true,'2026-09-07Z','2026-09-16Z','BASELINE','APPROVED_BASELINE'),"
                + "(?,?,true,true,true,'2026-09-07Z',NULL,'BASELINE','APPROVED_BASELINE'),"
                + "(?,?,false,false,false,'2026-09-16Z',NULL,'MANUAL','OBSERVED')",
                graph.store(), graph.former(), graph.store(), graph.current(), graph.store(), graph.former());
        return graph;
    }

    private Item item(Graph graph, UUID employee, boolean returned, String occurred,
            String category, String quantity, Item original) {
        Item item = new Item(UUID.randomUUID(), UUID.randomUUID(),
                original == null ? UUID.randomUUID() : original.product());
        if (original == null) {
            jdbc.update("INSERT INTO products(id,connection_id,source_system,external_id,name,source_kind) "
                    + "VALUES (?,?,'LIVESKLAD',?,'Synthetic','PRODUCT')", item.product(), graph.connection(),
                    item.product().toString());
        }
        jdbc.update("INSERT INTO sales_documents(id,connection_id,external_id,store_id,employee_id,"
                + "original_document_id,"
                + "document_kind,source_document_type,occurred_at,business_date,net_amount,last_sync_run_id) "
                + "VALUES (?,?,?,?,?,?,?,'sale',?::timestamptz,?::timestamptz::date,100,?)", item.document(),
                graph.connection(), item.document().toString(), graph.store(), employee,
                original == null ? null : original.document(), returned ? "RETURN" : "SALE",
                occurred, occurred, graph.run());
        jdbc.update("INSERT INTO sales_document_items(id,sales_document_id,external_id,original_item_id,product_id,"
                + "product_name_snapshot,analytics_category_id,condition_type_snapshot,quantity,unit_price,"
                + "gross_amount,"
                + "discount_amount,net_amount,cost_amount,cost_quality) SELECT ?,?,?,?,?,?,id,'NEW',"
                + "?::numeric,100,100,0,100,50,'KNOWN' FROM analytics_categories WHERE code=?",
                item.id(), item.document(),
                item.id().toString(), original == null ? null : original.id(), item.product(),
                category.equals("WARRANTY_GENERIC") ? "Check new" : "Synthetic", quantity, category);
        return item;
    }

    private record Graph(UUID store, UUID connection, UUID run, UUID former, UUID current) { }
    private record Item(UUID id, UUID document, UUID product) { }
}
