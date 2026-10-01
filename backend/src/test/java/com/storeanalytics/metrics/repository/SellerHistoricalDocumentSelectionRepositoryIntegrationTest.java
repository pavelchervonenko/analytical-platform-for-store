package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.repository.SellerHistoricalDocumentSelection.Bucket;
import com.storeanalytics.metrics.repository.SellerHistoricalDocumentSelection.Reason;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SellerHistoricalDocumentSelectionRepositoryIntegrationTest {

    private static final UUID STORE = UUID.fromString("00000000-0000-4000-8000-000000009301");
    private static final UUID EMPLOYEE = UUID.fromString("00000000-0000-4000-8000-000000009302");
    private static final UUID UNKNOWN_EMPLOYEE = UUID.fromString("00000000-0000-4000-8000-000000009303");
    private static final LocalDate START = LocalDate.of(2026, 8, 31);
    private static final LocalDate END = LocalDate.of(2026, 9, 12);

    @Test
    void resolvesReturnsFromOriginalSaleAndKeepsUnknownSeparateFromOutside() {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID connection = jdbc.queryForObject("""
                    SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'
                    """, UUID.class);
            UUID run = UUID.fromString("00000000-0000-4000-8000-000000009304");
            jdbc.update("""
                    INSERT INTO stores (id, connection_id, source_system, external_id, name)
                    VALUES (?, ?, 'LIVESKLAD', 'historical-selection-store', 'Synthetic store')
                    """, STORE, connection);
            jdbc.update("""
                    INSERT INTO employees (id, connection_id, source_system, external_id, full_name)
                    VALUES (?, ?, 'LIVESKLAD', 'historical-selection-employee', 'Synthetic employee'),
                           (?, ?, 'LIVESKLAD', 'historical-selection-unknown', 'Synthetic unknown')
                    """, EMPLOYEE, connection, UNKNOWN_EMPLOYEE, connection);
            jdbc.update("""
                    INSERT INTO sync_runs (id, connection_id, source_system, trigger_type,
                                           sync_scope, status)
                    VALUES (?, ?, 'LIVESKLAD', 'MANUAL', 'SALES', 'RUNNING')
                    """, run, connection);
            jdbc.update("""
                    INSERT INTO store_seller_membership_state
                        (store_id, authoritative_from, baseline_source)
                    VALUES (?, '2026-09-01T00:00:00Z', 'SYNTHETIC_VERIFIED_BASELINE')
                    """, STORE);
            jdbc.update("""
                    INSERT INTO seller_membership_history
                        (store_id, employee_id, employee_active, assignment_active,
                         participates_in_ranking, valid_from, valid_to,
                         change_source, effective_time_source)
                    VALUES (?, ?, true, true, true,
                            '2026-09-01T00:00:00Z', '2026-09-05T00:00:00Z',
                            'BASELINE', 'APPROVED_BASELINE'),
                           (?, ?, true, true, false,
                            '2026-09-05T00:00:00Z', NULL, 'MANUAL', 'OBSERVED')
                    """, STORE, EMPLOYEE, STORE, EMPLOYEE);

            UUID beforeBaseline = document(jdbc, connection, run, "SALE", null, EMPLOYEE,
                    "2026-08-31T12:00:00Z");
            UUID eligibleSale = document(jdbc, connection, run, "SALE", null, EMPLOYEE,
                    "2026-09-02T12:00:00Z");
            UUID ineligibleSale = document(jdbc, connection, run, "SALE", null, EMPLOYEE,
                    "2026-09-06T12:00:00Z");
            UUID eligibleReturn = document(jdbc, connection, run, "RETURN", eligibleSale, UNKNOWN_EMPLOYEE,
                    "2026-09-07T12:00:00Z");
            UUID ineligibleReturn = document(jdbc, connection, run, "RETURN", ineligibleSale, UNKNOWN_EMPLOYEE,
                    "2026-09-08T12:00:00Z");
            UUID unattributedSale = document(jdbc, connection, run, "SALE", null, null,
                    "2026-09-09T12:00:00Z");
            UUID unattributedReturn = document(jdbc, connection, run, "RETURN", unattributedSale, null,
                    "2026-09-10T12:00:00Z");
            UUID orphanReturn = document(jdbc, connection, run, "RETURN", null, null,
                    "2026-09-11T12:00:00Z");
            UUID historyGap = document(jdbc, connection, run, "SALE", null, UNKNOWN_EMPLOYEE,
                    "2026-09-12T12:00:00Z");

            var repository = new SellerHistoricalDocumentSelectionRepository(
                    new NamedParameterJdbcTemplate(jdbc));
            Map<UUID, SellerHistoricalDocumentSelection> selected = repository.read(STORE, START, END)
                    .stream().collect(Collectors.toMap(SellerHistoricalDocumentSelection::documentId,
                            Function.identity()));
            assertThat(selected).hasSize(9);
            assertSelection(selected, beforeBaseline, Bucket.UNKNOWN_MEMBERSHIP_HISTORY,
                    Reason.HISTORY_UNKNOWN);
            assertSelection(selected, eligibleSale, Bucket.SELLER_ELIGIBLE, Reason.NONE);
            assertSelection(selected, ineligibleSale, Bucket.KNOWN_OUTSIDE_SELLER_COHORT,
                    Reason.EXPLICITLY_INELIGIBLE_EMPLOYEE);
            assertSelection(selected, eligibleReturn, Bucket.SELLER_ELIGIBLE, Reason.NONE);
            assertThat(selected.get(eligibleReturn).employeeId()).isEqualTo(EMPLOYEE);
            assertThat(selected.get(eligibleReturn).membershipAt())
                    .isEqualTo(Instant.parse("2026-09-02T12:00:00Z"));
            assertSelection(selected, ineligibleReturn, Bucket.KNOWN_OUTSIDE_SELLER_COHORT,
                    Reason.EXPLICITLY_INELIGIBLE_EMPLOYEE);
            assertSelection(selected, unattributedSale, Bucket.KNOWN_OUTSIDE_SELLER_COHORT,
                    Reason.UNATTRIBUTED_SALE);
            assertSelection(selected, unattributedReturn, Bucket.KNOWN_OUTSIDE_SELLER_COHORT,
                    Reason.LINKED_TO_UNATTRIBUTED_ORIGINAL);
            assertSelection(selected, orphanReturn, Bucket.ORPHAN_RETURN,
                    Reason.MISSING_OR_INVALID_ORIGINAL);
            assertSelection(selected, historyGap, Bucket.UNKNOWN_MEMBERSHIP_HISTORY,
                    Reason.HISTORY_UNKNOWN);

            jdbc.update("UPDATE sales_documents SET is_deleted = true WHERE id = ?", eligibleSale);
            Map<UUID, SellerHistoricalDocumentSelection> afterDeletion = repository.read(STORE, START, END)
                    .stream().collect(Collectors.toMap(SellerHistoricalDocumentSelection::documentId,
                            Function.identity()));
            assertThat(afterDeletion).doesNotContainKey(eligibleSale);
            assertSelection(afterDeletion, eligibleReturn, Bucket.SELLER_ELIGIBLE, Reason.NONE);
        }
    }

    private UUID document(JdbcTemplate jdbc, UUID connection, UUID run, String kind, UUID original,
                          UUID employee, String occurredAt) {
        UUID id = UUID.randomUUID();
        Instant time = Instant.parse(occurredAt);
        jdbc.update("""
                INSERT INTO sales_documents
                    (id, connection_id, external_id, store_id, employee_id, original_document_id,
                     document_kind, source_document_type, occurred_at, business_date,
                     net_amount, last_sync_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'synthetic', ?, ?, 0, ?)
                """, id, connection, id.toString(), STORE, employee, original, kind,
                Timestamp.from(time), LocalDate.ofInstant(time, java.time.ZoneOffset.UTC), run);
        return id;
    }

    private void assertSelection(Map<UUID, SellerHistoricalDocumentSelection> selected, UUID id,
                                 Bucket bucket, Reason reason) {
        assertThat(selected.get(id).bucket()).isEqualTo(bucket);
        assertThat(selected.get(id).reason()).isEqualTo(reason);
    }
}
