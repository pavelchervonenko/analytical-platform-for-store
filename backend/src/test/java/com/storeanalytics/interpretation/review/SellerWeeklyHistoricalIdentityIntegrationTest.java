package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerHistoricalComparisonFacts;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SellerWeeklyHistoricalIdentityIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static final ClosedSellerWeek WEEK = new ClosedSellerWeek(LocalDate.parse("2026-09-14"), ZoneOffset.UTC);
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static SellerWeeklyHistoricalIdentity identity;

    @BeforeAll
    static void initialize() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        identity = new SellerWeeklyHistoricalIdentity(jdbc);
    }

    @Test
    void canonicalIntervalsDifferFromCurrentRosterAndFutureChangesDoNotRewritePeriodSelection() {
        Graph graph = seed("2026-09-01T00:00:00Z");
        UUID seller = employee(graph);
        interval(graph, seller, "2026-09-01T00:00:00Z", null, true);
        var facts = facts(graph, List.of(seller), Set.of(seller));
        var first = read(graph, facts);
        assertThat(first.selectionHash()).isNotEqualTo(new SellerCohortSnapshot(graph.store(), List.of(seller))
                .fingerprint());
        assertThat(first.selectionHash()).isNotEqualTo(first.actionabilityHash());
        jdbc.update("UPDATE seller_membership_history SET valid_to='2026-10-01T00:00:00Z' "
                + "WHERE store_id=? AND employee_id=? AND valid_to IS NULL", graph.store(), seller);
        interval(graph, seller, "2026-10-01T00:00:00Z", null, false);
        jdbc.update("UPDATE store_seller_membership_state SET membership_revision=1 WHERE store_id=?", graph.store());
        var after = read(graph, facts(graph, List.of(seller), Set.of()));
        assertThat(after.selectionHash()).isEqualTo(first.selectionHash());
        assertThat(after.actionabilityHash()).isNotEqualTo(first.actionabilityHash());
        assertThat(after.revision()).isOne();
    }

    @Test
    void toggleInsideWeekChangesSelectionEvenWhenUnionOfEmployeesIsUnchanged() {
        Graph graph = seed("2026-09-01T00:00:00Z");
        UUID seller = employee(graph);
        interval(graph, seller, "2026-09-01T00:00:00Z", null, true);
        var facts = facts(graph, List.of(seller), Set.of(seller));
        var first = read(graph, facts);
        jdbc.update("UPDATE seller_membership_history SET valid_to='2026-09-16T12:00:00Z' "
                + "WHERE store_id=? AND employee_id=? AND valid_to IS NULL", graph.store(), seller);
        interval(graph, seller, "2026-09-16T12:00:00Z", null, false);
        var after = read(graph, facts);
        assertThat(after.selectionHash()).isNotEqualTo(first.selectionHash());
        assertThat(after.actionabilityHash()).isEqualTo(first.actionabilityHash());
    }

    @Test
    void rowInsertionOrderAndPersonalLabelsAreNotSemanticIdentity() {
        Graph graph = seed("2026-09-01T00:00:00Z");
        UUID first = employee(graph);
        UUID second = employee(graph);
        interval(graph, second, "2026-09-01T00:00:00Z", null, true);
        interval(graph, first, "2026-09-01T00:00:00Z", null, true);
        var a = read(graph, facts(graph, List.of(second, first), Set.of(second, first)));
        jdbc.update("UPDATE employees SET full_name='Renamed synthetic' WHERE id=?", first);
        var b = read(graph, facts(graph, List.of(first, second), Set.of(first, second)));
        assertThat(a).isEqualTo(b);
        assertThat(a.selectionHash()).hasSize(64);
    }

    @Test
    void missingLateBaselineAndMismatchedCohortAreRejectedNotTurnedIntoEmptyHistory() {
        Graph missing = seed(null);
        assertThatThrownBy(() -> read(missing, facts(missing, List.of(), Set.of())))
                .hasMessage("HISTORY_BASELINE_UNAVAILABLE");
        Graph late = seed("2026-09-07T00:00:00.000001Z");
        assertThatThrownBy(() -> read(late, facts(late, List.of(), Set.of())))
                .hasMessage("HISTORY_BASELINE_UNAVAILABLE");
        Graph graph = seed("2026-09-01T00:00:00Z");
        UUID seller = employee(graph);
        interval(graph, seller, "2026-09-01T00:00:00Z", null, true);
        assertThatThrownBy(() -> read(graph, facts(graph, List.of(), Set.of())))
                .hasMessage("HISTORICAL_COHORT_CHANGED");
    }

    @Test
    void identityCannotBeReadOutsideTheFactsTransactionOrAtWeakerIsolation() {
        Graph graph = seed("2026-09-01T00:00:00Z");
        var facts = facts(graph, List.of(), Set.of());
        assertThatThrownBy(() -> identity.read(graph.store(), WEEK, facts)).isInstanceOf(IllegalStateException.class);
        var weak = new TransactionTemplate(transaction.getTransactionManager());
        assertThatThrownBy(() -> weak.execute(status -> identity.read(graph.store(), WEEK, facts)))
                .isInstanceOf(IllegalStateException.class);
    }

    private SellerWeeklyHistoricalMembership read(Graph graph, SellerHistoricalComparisonFacts facts) {
        return transaction.execute(status -> identity.read(graph.store(), WEEK, facts));
    }

    private SellerHistoricalComparisonFacts facts(Graph graph, List<UUID> ids, Set<UUID> actionIds) {
        var comparison = mock(SellerPeriodComparisonFacts.class, RETURNS_DEEP_STUBS);
        when(comparison.current().metrics().cohort()).thenReturn(new SellerCohortSnapshot(graph.store(), ids));
        return new SellerHistoricalComparisonFacts(comparison, actionIds);
    }

    private Graph seed(String baseline) {
        UUID connection = jdbc.queryForObject("SELECT id FROM integration_connections "
                + "WHERE connection_key='livesklad-default'", UUID.class);
        UUID store = UUID.randomUUID();
        jdbc.update("INSERT INTO stores(id,connection_id,source_system,external_id,name,timezone) "
                + "VALUES (?,?,'LIVESKLAD',?,'Synthetic identity','UTC')", store, connection, store.toString());
        if (baseline != null) {
            jdbc.update("INSERT INTO store_seller_membership_state(store_id,authoritative_from,baseline_source) "
                    + "VALUES (?,?::timestamptz,'SYNTHETIC_ONLY')", store, baseline);
        }
        return new Graph(store, connection);
    }

    private UUID employee(Graph graph) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO employees(id,connection_id,source_system,external_id,full_name) "
                + "VALUES (?,?,'LIVESKLAD',?,'Synthetic employee')", id, graph.connection(), id.toString());
        return id;
    }

    private void interval(Graph graph, UUID employee, String from, String to, boolean eligible) {
        jdbc.update("""
                INSERT INTO seller_membership_history(store_id,employee_id,employee_active,assignment_active,
                    participates_in_ranking,valid_from,valid_to,change_source,effective_time_source)
                VALUES (?,?,true,true,?,?::timestamptz,?::timestamptz,'BASELINE','APPROVED_BASELINE')
                """, graph.store(), employee, eligible, from, to);
    }

    private record Graph(UUID store, UUID connection) { }
}
