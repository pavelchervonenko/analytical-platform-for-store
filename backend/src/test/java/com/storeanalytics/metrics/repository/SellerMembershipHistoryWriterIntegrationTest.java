package com.storeanalytics.metrics.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.metrics.repository.SellerMembershipHistoryRepository.Eligibility;
import com.storeanalytics.metrics.repository.SellerMembershipHistoryWriter.ChangeSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SellerMembershipHistoryWriterIntegrationTest {

    private static final UUID STORE = UUID.fromString("00000000-0000-4000-8000-000000009111");
    private static final UUID EMPLOYEE = UUID.fromString("00000000-0000-4000-8000-000000009112");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-4000-8000-000000009113");

    @Test
    void bootstrapAndForwardToggleAreAtomicAndNoOpDoesNotAdvanceRevision() {
        try (var postgres = new PostgreSQLContainer("postgres:16-alpine")) {
            postgres.start();
            var source = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("""
                    INSERT INTO stores (id, connection_id, source_system, external_id, name)
                    SELECT ?, id, 'LIVESKLAD', 'membership-writer-store', 'Synthetic store'
                    FROM integration_connections WHERE connection_key = 'livesklad-default'
                    """, STORE);
            jdbc.update("""
                    INSERT INTO employees (id, connection_id, source_system, external_id, full_name)
                    SELECT ?, id, 'LIVESKLAD', 'membership-writer-employee', 'Synthetic employee'
                    FROM integration_connections WHERE connection_key = 'livesklad-default'
                    """, EMPLOYEE);
            jdbc.update("""
                    INSERT INTO employee_store_assignments
                        (employee_id, store_id, is_active, participates_in_ranking)
                    VALUES (?, ?, true, true)
                    """, EMPLOYEE, STORE);
            var writer = new SellerMembershipHistoryWriter(jdbc);
            var reader = new SellerMembershipHistoryRepository(jdbc);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            assertThat(reader.eligibilityAt(STORE, EMPLOYEE, Instant.now())).isEqualTo(Eligibility.UNKNOWN);
            assertThatThrownBy(() -> writer.bootstrapStore(STORE, "VERIFIED_BASELINE"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat((Boolean) transaction.execute(status -> writer.bootstrapStore(STORE, "VERIFIED_BASELINE")))
                    .isTrue();
            assertThat((Boolean) transaction.execute(status -> writer.bootstrapStore(STORE, "VERIFIED_BASELINE")))
                    .isFalse();
            Timestamp initial = jdbc.queryForObject("""
                    SELECT valid_from FROM seller_membership_history
                    WHERE store_id = ? AND employee_id = ?
                    """, Timestamp.class, STORE, EMPLOYEE);
            assertThat(reader.eligibilityAt(STORE, EMPLOYEE, initial.toInstant()))
                    .isEqualTo(Eligibility.ELIGIBLE);

            assertThat((Boolean) transaction.execute(status -> {
                writer.lockStore(STORE);
                jdbc.update("""
                        UPDATE employee_store_assignments SET participates_in_ranking = false
                        WHERE store_id = ? AND employee_id = ?
                        """, STORE, EMPLOYEE);
                return writer.reconcileStore(STORE, ChangeSource.MANUAL, ACTOR, null);
            })).isTrue();
            Timestamp changedAt = jdbc.queryForObject("""
                    SELECT valid_from FROM seller_membership_history
                    WHERE store_id = ? AND employee_id = ? AND valid_to IS NULL
                    """, Timestamp.class, STORE, EMPLOYEE);
            assertThat(reader.eligibilityAt(STORE, EMPLOYEE, initial.toInstant()))
                    .isEqualTo(Eligibility.ELIGIBLE);
            assertThat(reader.eligibilityAt(STORE, EMPLOYEE, changedAt.toInstant()))
                    .isEqualTo(Eligibility.NOT_ELIGIBLE);
            assertThat((Boolean) transaction.execute(status -> writer.reconcileStore(
                    STORE, ChangeSource.MANUAL, ACTOR, null))).isFalse();
            assertThat(jdbc.queryForObject("""
                    SELECT membership_revision FROM store_seller_membership_state WHERE store_id = ?
                    """, Long.class, STORE)).isOne();

            assertThatThrownBy(() -> transaction.execute(status -> {
                writer.lockStore(STORE);
                jdbc.update("""
                        UPDATE employee_store_assignments SET participates_in_ranking = true
                        WHERE store_id = ? AND employee_id = ?
                        """, STORE, EMPLOYEE);
                writer.reconcileStore(STORE, ChangeSource.MANUAL, ACTOR, null);
                throw new IllegalStateException("synthetic rollback");
            })).hasMessageContaining("synthetic rollback");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM seller_membership_history
                    WHERE store_id = ? AND employee_id = ?
                    """, Integer.class, STORE, EMPLOYEE)).isEqualTo(2);
            assertThat(reader.eligibilityAt(STORE, EMPLOYEE, changedAt.toInstant()))
                    .isEqualTo(Eligibility.NOT_ELIGIBLE);
        }
    }
}
