package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class HistoricalSalesWeeklyStabilityIntegrationTest {

    private static final Instant START = Instant.parse("2026-08-10T00:00:00Z");
    private static final Instant END = START.plusSeconds(7200);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static SellerWeeklySourceStabilityRepository repository;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()
        );
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new SellerWeeklySourceStabilityRepository(new NamedParameterJdbcTemplate(jdbc));
    }

    @ParameterizedTest
    @CsvSource({"SALES,BACKFILL", "RETURNS,BACKFILL", "ORDERS,INCREMENTAL",
        "STORES,BACKFILL", "EMPLOYEES,INCREMENTAL"})
    void historicalSaleSuccessCannotRepairAnIncompleteFullPhaseJob(
            String failedPhase, String repairType
    ) {
        TestStore store = store();
        job(store, "INCREMENTAL", failedPhase, "FAILED", START, END, END.plusSeconds(60));
        job(store, "HISTORICAL_SALES", "SALES", "SUCCESS", START, END, END.plusSeconds(120));

        assertThat(state(store)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        job(store, repairType, "ORDERS", "SUCCESS", START, END, END.plusSeconds(180));
        assertThat(state(store)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "WAITING_RETRY", "FAILED", "CANCELLED"})
    void overlappingHistoricalActivityStillHoldsFactsAndOutsideActivityDoesNot(String status) {
        TestStore overlapping = store();
        job(overlapping, "HISTORICAL_SALES", "SALES", status, START, END,
                status.equals("FAILED") || status.equals("CANCELLED") ? END.plusSeconds(60) : null);
        SellerWeeklySourceStability expected = status.equals("FAILED") || status.equals("CANCELLED")
                ? SellerWeeklySourceStability.NEEDS_RECONCILIATION
                : SellerWeeklySourceStability.IN_PROGRESS;
        assertThat(state(overlapping)).isEqualTo(expected);

        TestStore outside = store();
        Instant later = END.plusSeconds(86_400);
        job(outside, "HISTORICAL_SALES", "SALES", status, later, later.plusSeconds(7200),
                status.equals("FAILED") || status.equals("CANCELLED") ? later.plusSeconds(10_800) : null);
        assertThat(state(outside)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @Test
    void successfulHistoricalMetadataStillAdvancesTheFactsSourceRevision() {
        TestStore store = store();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM store_analytics_source_state WHERE store_id = ?",
                Integer.class, store.id()
        )).isZero();
        long before = 0;
        UUID historical = job(store, "HISTORICAL_SALES", "SALES", "RUNNING",
                START, END, null);
        long running = revision(store);
        assertThat(running).isGreaterThan(before);
        assertThat(state(store)).isEqualTo(SellerWeeklySourceStability.IN_PROGRESS);

        jdbc.update("UPDATE sync_jobs SET status = 'SUCCESS', finished_at = ?, "
                        + "lease_owner = NULL, lease_until = NULL WHERE id = ?",
                Timestamp.from(END.plusSeconds(60)), historical);

        assertThat(revision(store)).isGreaterThan(running);
        assertThat(state(store)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED"})
    void failedHistoricalSaleJobCanBeRepairedByLaterCompleteHistoricalCoverage(String status) {
        TestStore store = store();
        job(store, "HISTORICAL_SALES", "SALES", status, START, END, END.plusSeconds(60));
        job(store, "HISTORICAL_SALES", "SALES", "SUCCESS", START, END, END.plusSeconds(30));
        assertThat(state(store)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        job(store, "HISTORICAL_SALES", "SALES", "SUCCESS", START, START.plusSeconds(3600),
                END.plusSeconds(120));
        assertThat(state(store)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        job(store, "HISTORICAL_SALES", "SALES", "SUCCESS", START, END, END.plusSeconds(180));
        assertThat(state(store)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    private long revision(TestStore store) {
        return jdbc.queryForObject("SELECT revision FROM store_analytics_source_state WHERE store_id = ?",
                Long.class, store.id());
    }

    private SellerWeeklySourceStability state(TestStore store) {
        return repository.read(store.id(), START, END);
    }

    private TestStore store() {
        UUID connection = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO integration_connections (id, connection_key, source_system, display_name)
                VALUES (?, ?, 'LIVESKLAD', 'Synthetic stability connection')
                """, connection, "historical-stability-" + connection);
        jdbc.update("""
                INSERT INTO stores (id, connection_id, source_system, external_id, name, timezone)
                VALUES (?, ?, 'LIVESKLAD', ?, 'Synthetic stability store', 'UTC')
                """, id, connection, id.toString());
        return new TestStore(id, connection);
    }

    private UUID job(TestStore store, String type, String phase, String status, Instant from,
            Instant to, Instant finished) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sync_jobs (id, connection_id, job_type, status, phase,
                    period_start, period_end, cursor_start, current_window_end,
                    window_size_minutes, max_attempts, next_attempt_at, started_at, finished_at,
                    lease_owner, lease_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 180, 3, ?, ?, ?, ?, ?)
                """, id, store.connectionId(), type, status, phase,
                Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(from), finished == null ? null : Timestamp.from(finished),
                status.equals("RUNNING") ? "synthetic-history-worker" : null,
                status.equals("RUNNING") ? Timestamp.from(from.plusSeconds(60)) : null);
        return id;
    }

    private record TestStore(UUID id, UUID connectionId) {
    }
}
