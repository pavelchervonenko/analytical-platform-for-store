package com.storeanalytics.common.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository.Eligibility;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SellerMembershipHistoryMigrationIntegrationTest {

    @Test
    void startsWithoutRetroactiveBaselineAndProtectsIntervals() {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM store_seller_membership_state", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM seller_membership_history", Integer.class))
                    .isZero();

            jdbc.update("""
                    INSERT INTO stores (id, connection_id, source_system, external_id, name)
                    SELECT '00000000-0000-4000-8000-000000009101', id, 'LIVESKLAD',
                           'membership-test-store', 'Membership test store'
                    FROM integration_connections WHERE connection_key = 'livesklad-default'
                    """);
            jdbc.update("""
                    INSERT INTO employees (id, connection_id, source_system, external_id, full_name)
                    SELECT '00000000-0000-4000-8000-000000009102', id, 'LIVESKLAD',
                           'membership-test-employee', 'Synthetic employee'
                    FROM integration_connections WHERE connection_key = 'livesklad-default'
                    """);
            var history = new SellerMembershipHistoryRepository(jdbc);
            UUID storeId = UUID.fromString("00000000-0000-4000-8000-000000009101");
            UUID employeeId = UUID.fromString("00000000-0000-4000-8000-000000009102");
            assertThat(history.eligibilityAt(storeId, employeeId,
                    Instant.parse("2026-10-01T12:00:00Z"))).isEqualTo(Eligibility.UNKNOWN);
            jdbc.update("""
                    INSERT INTO store_seller_membership_state
                        (store_id, authoritative_from, baseline_source)
                    VALUES ('00000000-0000-4000-8000-000000009101',
                            '2026-09-30T21:00:00Z', 'VERIFIED_LOCAL_BASELINE')
                    """);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO seller_membership_history
                        (employee_id, store_id, employee_active, assignment_active,
                         participates_in_ranking, valid_from, change_source, effective_time_source)
                    VALUES ('00000000-0000-4000-8000-000000009102',
                            '00000000-0000-4000-8000-000000009101', true, true, true,
                            '2026-09-30T20:00:00Z', 'BASELINE', 'APPROVED_BASELINE')
                    """)).hasStackTraceContaining("predates verified baseline");
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE store_seller_membership_state
                    SET authoritative_from = '2026-09-29T21:00:00Z'
                    WHERE store_id = '00000000-0000-4000-8000-000000009101'
                    """)).hasStackTraceContaining("baseline is immutable");
            jdbc.update("""
                    INSERT INTO seller_membership_history
                        (employee_id, store_id, employee_active, assignment_active,
                         participates_in_ranking, valid_from, change_source, effective_time_source)
                    VALUES ('00000000-0000-4000-8000-000000009102',
                            '00000000-0000-4000-8000-000000009101', true, true, true,
                            '2026-09-30T21:00:00Z', 'BASELINE', 'APPROVED_BASELINE')
                    """);
            UUID foreignConnection = jdbc.queryForObject("""
                    INSERT INTO integration_connections
                        (connection_key, source_system, display_name)
                    VALUES ('membership-foreign', 'LIVESKLAD', 'Synthetic foreign connection')
                    RETURNING id
                    """, UUID.class);
            jdbc.update("""
                    INSERT INTO employees
                        (id, connection_id, source_system, external_id, full_name)
                    VALUES ('00000000-0000-4000-8000-000000009103', ?, 'LIVESKLAD',
                            'membership-foreign-employee', 'Synthetic foreign employee')
                    """, foreignConnection);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO seller_membership_history
                        (employee_id, store_id, employee_active, assignment_active,
                         participates_in_ranking, valid_from, change_source, effective_time_source)
                    VALUES ('00000000-0000-4000-8000-000000009103',
                            '00000000-0000-4000-8000-000000009101', true, true, true,
                            '2026-10-01T00:00:00Z', 'SYNC', 'OBSERVED')
                    """)).hasStackTraceContaining("same connection");
            assertThat(history.eligibilityAt(storeId, employeeId,
                    Instant.parse("2026-09-30T20:59:59Z"))).isEqualTo(Eligibility.UNKNOWN);
            assertThat(history.eligibilityAt(storeId, employeeId,
                    Instant.parse("2026-10-01T12:00:00Z"))).isEqualTo(Eligibility.ELIGIBLE);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO seller_membership_history
                        (employee_id, store_id, employee_active, assignment_active,
                         participates_in_ranking, valid_from, change_source, effective_time_source)
                    VALUES ('00000000-0000-4000-8000-000000009102',
                            '00000000-0000-4000-8000-000000009101', true, true, false,
                            '2026-10-01T00:00:00Z', 'MANUAL', 'OBSERVED')
                    """)).hasStackTraceContaining("ex_seller_membership_history_no_overlap");
            jdbc.update("""
                    UPDATE seller_membership_history SET valid_to = '2026-10-02T00:00:00Z'
                    WHERE employee_id = '00000000-0000-4000-8000-000000009102'
                    """);
            jdbc.update("""
                    INSERT INTO seller_membership_history
                        (employee_id, store_id, employee_active, assignment_active,
                         participates_in_ranking, valid_from, change_source, effective_time_source)
                    VALUES ('00000000-0000-4000-8000-000000009102',
                            '00000000-0000-4000-8000-000000009101', true, true, false,
                            '2026-10-02T00:00:00Z', 'MANUAL', 'OBSERVED')
                    """);
            assertThat(history.eligibilityAt(storeId, employeeId,
                    Instant.parse("2026-10-01T12:00:00Z"))).isEqualTo(Eligibility.ELIGIBLE);
            assertThat(history.eligibilityAt(storeId, employeeId,
                    Instant.parse("2026-10-02T00:00:00Z"))).isEqualTo(Eligibility.NOT_ELIGIBLE);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM seller_membership_history
                    WHERE store_id = '00000000-0000-4000-8000-000000009101'
                      AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)
                      AND participates_in_ranking
                    """, Integer.class, Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")),
                    Timestamp.from(Instant.parse("2026-10-01T12:00:00Z")))).isOne();
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE seller_membership_history SET participates_in_ranking = true
                    WHERE valid_to IS NULL
                    """)).hasStackTraceContaining("append-only");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM seller_membership_history"))
                    .hasStackTraceContaining("append-only");
            assertThatThrownBy(() -> jdbc.execute("TRUNCATE seller_membership_history"))
                    .hasStackTraceContaining("cannot be truncated");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM store_seller_membership_state"))
                    .hasStackTraceContaining("baseline is immutable");
            assertThatThrownBy(() -> jdbc.execute("TRUNCATE store_seller_membership_state"))
                    .hasStackTraceContaining("cannot truncate a table referenced");
        }
    }
}
