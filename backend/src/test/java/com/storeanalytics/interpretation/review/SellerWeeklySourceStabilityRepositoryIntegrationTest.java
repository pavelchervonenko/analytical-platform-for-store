package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class SellerWeeklySourceStabilityRepositoryIntegrationTest {

    private static final Instant START = Instant.parse("2026-08-10T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-24T00:00:00Z");
    private static final Instant MIDDLE = Instant.parse("2026-08-17T00:00:00Z");
    private static final PeriodContext PERIOD = new PeriodContext("UTC",
            new DateRange(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23)),
            new DateRange(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)),
            "Current", "Previous");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private SellerWeeklySourceStabilityRepository repository;

    @Autowired
    private SellerWeeklySourceCoverageRepository coverageRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM sync_runs");
        jdbc.update("DELETE FROM sync_jobs");
        jdbc.update("DELETE FROM stores");
    }

    @Test
    void ignoresOtherStoreButDetectsOrdersAndOtherRelevantDirectRuns() {
        UUID connection = connection();
        UUID target = store(connection);
        UUID other = store(connection);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);

        run(connection, other, "SALES", "RUNNING", START, END, null);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);

        UUID orders = run(connection, target, "ORDERS", "RUNNING", START, END, null);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.IN_PROGRESS);
        jdbc.update("DELETE FROM sync_runs WHERE id = ?", orders);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);

        UUID relevant = run(connection, target, "RETURNS", "RUNNING", START, END, null);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.IN_PROGRESS);
        jdbc.update("DELETE FROM sync_runs WHERE id = ?", relevant);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @Test
    void failureAndPartialSuccessNeedLaterFullReconciliation() {
        UUID connection = connection();
        UUID target = store(connection);
        run(connection, target, "SALES", "FAILED", START, END, END.plusSeconds(60));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        run(connection, target, "SALES", "PARTIAL_SUCCESS", START, END, END.plusSeconds(120));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        run(connection, target, "SALES", "SUCCESS", START, END, END.plusSeconds(180));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "PARTIAL_SUCCESS", "CANCELLED"})
    void incompleteDirectRunIsNotReconciledByOldSuccessOrARecentPartialWindow(String status) {
        UUID connection = connection();
        UUID target = store(connection);
        UUID other = store(connection);
        run(connection, target, "RETURNS", "SUCCESS", START, END, END.plusSeconds(60));
        run(connection, target, "RETURNS", status, START, END, END.plusSeconds(120));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        run(connection, other, "RETURNS", "SUCCESS", START, END, END.plusSeconds(180));
        run(connection, target, "RETURNS", "SUCCESS", START, MIDDLE, END.plusSeconds(180));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        run(connection, target, "RETURNS", "SUCCESS", START, END, END.plusSeconds(240));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED"})
    void incompleteJobRequiresLaterFullReconciliation(String status) {
        UUID connection = connection();
        UUID target = store(connection);
        UUID older = job(connection, "ORDERS", START, END);
        jdbc.update("UPDATE sync_jobs SET status = 'SUCCESS', finished_at = ? WHERE id = ?",
                Timestamp.from(END.plusSeconds(60)), older);
        UUID incomplete = job(connection, "ORDERS", START, END);
        jdbc.update("UPDATE sync_jobs SET status = ?, finished_at = ? WHERE id = ?",
                status, Timestamp.from(END.plusSeconds(120)), incomplete);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        UUID partial = job(connection, "ORDERS", START, MIDDLE);
        jdbc.update("UPDATE sync_jobs SET status = 'SUCCESS', finished_at = ? WHERE id = ?",
                Timestamp.from(END.plusSeconds(180)), partial);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        UUID later = job(connection, "ORDERS", START, END);
        jdbc.update("UPDATE sync_jobs SET status = 'SUCCESS', finished_at = ? WHERE id = ?",
                Timestamp.from(END.plusSeconds(240)), later);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @Test
    void consecutiveSuccessWindowsReconcileAnIncompleteRunButAGapDoesNot() {
        UUID connection = connection();
        UUID target = store(connection);
        run(connection, target, "RETURNS", "CANCELLED", START, END, END.plusSeconds(60));
        run(connection, target, "RETURNS", "SUCCESS", START, MIDDLE, END.plusSeconds(120));
        run(connection, target, "RETURNS", "SUCCESS", MIDDLE.plusSeconds(3600), END,
                END.plusSeconds(180));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        run(connection, target, "RETURNS", "SUCCESS", MIDDLE, MIDDLE.plusSeconds(3600),
                END.plusSeconds(240));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @Test
    void consecutiveSuccessfulJobsReconcileAnIncompleteJobButAGapDoesNot() {
        UUID connection = connection();
        UUID target = store(connection);
        UUID cancelled = job(connection, "ORDERS", START, END);
        finishJob(cancelled, "CANCELLED", END.plusSeconds(60));
        UUID first = job(connection, "ORDERS", START, MIDDLE);
        finishJob(first, "SUCCESS", END.plusSeconds(120));
        UUID second = job(connection, "ORDERS", MIDDLE.plusSeconds(3600), END);
        finishJob(second, "SUCCESS", END.plusSeconds(180));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        UUID missing = job(connection, "ORDERS", MIDDLE, MIDDLE.plusSeconds(3600));
        finishJob(missing, "SUCCESS", END.plusSeconds(240));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @Test
    void membershipJobReconciliationIsIndependentOfTheRequestedFinancialPeriod() {
        UUID connection = connection();
        UUID target = store(connection);
        UUID cancelled = job(connection, "EMPLOYEES", START, MIDDLE);
        finishJob(cancelled, "CANCELLED", END.plusSeconds(60));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        UUID later = job(connection, "ORDERS", END.plusSeconds(86_400), END.plusSeconds(172_800));
        finishJob(later, "SUCCESS", END.plusSeconds(172_800));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @Test
    void undatedSuccessCannotReconcileAnUndatedFinancialFailure() {
        UUID connection = connection();
        UUID target = store(connection);
        UUID incomplete = run(connection, target, "RETURNS", "FAILED", START, END,
                END.plusSeconds(60));
        jdbc.update("UPDATE sync_runs SET period_end = NULL WHERE id = ?", incomplete);
        UUID undated = run(connection, target, "RETURNS", "SUCCESS", START, END,
                END.plusSeconds(120));
        jdbc.update("UPDATE sync_runs SET period_start = NULL, period_end = NULL WHERE id = ?", undated);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        run(connection, target, "RETURNS", "SUCCESS", START, END, END.plusSeconds(180));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @Test
    void financialCancellationOutsideTheReportWeeksDoesNotBlockTheReport() {
        UUID connection = connection();
        UUID target = store(connection);
        Instant laterStart = END.plusSeconds(86_400);
        Instant laterEnd = END.plusSeconds(172_800);
        run(connection, target, "SALES", "CANCELLED", laterStart, laterEnd, laterEnd);
        UUID cancelled = job(connection, "SALES", laterStart, laterEnd);
        jdbc.update("UPDATE sync_jobs SET status = 'CANCELLED', finished_at = ? WHERE id = ?",
                Timestamp.from(laterEnd), cancelled);

        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);
    }

    @Test
    void activeJobIsScopedByPeriodExceptForMembershipPhase() {
        UUID connection = connection();
        UUID target = store(connection);
        UUID job = job(connection, "SALES", END.plusSeconds(86_400),
                END.plusSeconds(172_800));
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);

        jdbc.update("UPDATE sync_jobs SET phase = 'EMPLOYEES' WHERE id = ?", job);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.IN_PROGRESS);

        jdbc.update("UPDATE sync_jobs SET phase = 'SALES', period_start = ?, period_end = ?, "
                        + "cursor_start = ?, current_window_end = ? WHERE id = ?",
                Timestamp.from(START), Timestamp.from(END), Timestamp.from(START),
                Timestamp.from(START.plusSeconds(3600)), job);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.IN_PROGRESS);

        jdbc.update("UPDATE sync_jobs SET phase = 'ORDERS' WHERE id = ?", job);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.IN_PROGRESS);
    }

    @Test
    void failedSellerOrOrderJobNeedsLaterSuccessfulReconciliation() {
        UUID connection = connection();
        UUID target = store(connection);
        UUID failed = job(connection, "SALES", START, END);
        jdbc.update("UPDATE sync_jobs SET status = 'FAILED', finished_at = ? WHERE id = ?",
                Timestamp.from(END.plusSeconds(60)), failed);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);

        UUID later = job(connection, "RETURNS", START, END);
        jdbc.update("UPDATE sync_jobs SET status = 'SUCCESS', finished_at = ? WHERE id = ?",
                Timestamp.from(END.plusSeconds(120)), later);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.STABLE);

        jdbc.update("UPDATE sync_jobs SET phase = 'ORDERS' WHERE id = ?", failed);
        jdbc.update("DELETE FROM sync_jobs WHERE id = ?", later);
        assertThat(state(target)).isEqualTo(SellerWeeklySourceStability.NEEDS_RECONCILIATION);
    }

    @Test
    void continuousSuccessWindowsCoverBothWeeksButGapAndPartialDoNot() {
        UUID connection = connection();
        UUID target = store(connection);
        UUID other = store(connection);
        run(connection, other, "ORDERS", "SUCCESS", START, END, END);
        assertThat(coverageRepository.read(target, PERIOD).orders())
                .isEqualTo(new SellerWeeklySourceCoverage.Window(false, false));

        run(connection, target, "SALES", "SUCCESS", START, MIDDLE, END);
        run(connection, target, "SALES", "SUCCESS", MIDDLE, END, END);
        run(connection, target, "RETURNS", "SUCCESS", START, END, END);
        run(connection, target, "ORDERS", "PARTIAL_SUCCESS", START, END, END);
        SellerWeeklySourceCoverage before = coverageRepository.read(target, PERIOD);
        assertThat(before.sales()).isEqualTo(new SellerWeeklySourceCoverage.Window(true, true));
        assertThat(before.returns()).isEqualTo(new SellerWeeklySourceCoverage.Window(true, true));
        assertThat(before.orders()).isEqualTo(new SellerWeeklySourceCoverage.Window(false, false));

        run(connection, target, "ORDERS", "SUCCESS", START, END, END.plusSeconds(60));
        assertThat(coverageRepository.read(target, PERIOD))
                .isEqualTo(SellerWeeklySourceCoverage.complete());
    }

    @Test
    void latestEndDoesNotHideGapInPreviousWeek() {
        UUID connection = connection();
        UUID target = store(connection);
        run(connection, target, "SALES", "SUCCESS", START, MIDDLE.minusSeconds(3600), END);
        run(connection, target, "SALES", "SUCCESS", MIDDLE, END, END);

        assertThat(coverageRepository.read(target, PERIOD).sales())
                .isEqualTo(new SellerWeeklySourceCoverage.Window(true, false));
    }

    private SellerWeeklySourceStability state(UUID store) {
        return repository.read(store, START, END);
    }

    private UUID connection() {
        return jdbc.queryForObject("SELECT id FROM integration_connections "
                + "WHERE connection_key = 'livesklad-default'", UUID.class);
    }

    private UUID store(UUID connection) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stores (id, connection_id, source_system, external_id, name) "
                + "VALUES (?, ?, 'LIVESKLAD', ?, 'Synthetic')", id, connection, id.toString());
        return id;
    }

    private UUID run(UUID connection, UUID store, String scope, String status,
            Instant from, Instant to, Instant finished) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sync_runs (id, connection_id, store_id, source_system,
                    trigger_type, sync_scope, status, period_start, period_end, started_at,
                    finished_at)
                VALUES (?, ?, ?, 'LIVESKLAD', 'SCHEDULED', ?, ?, ?, ?, ?, ?)
                """, id, connection, store, scope, status, Timestamp.from(from),
                Timestamp.from(to), Timestamp.from(from),
                finished == null ? null : Timestamp.from(finished));
        return id;
    }

    private UUID job(UUID connection, String phase, Instant from, Instant to) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sync_jobs (id, connection_id, job_type, status, phase,
                    period_start, period_end, cursor_start, current_window_end,
                    window_size_minutes, max_attempts, next_attempt_at)
                VALUES (?, ?, 'INCREMENTAL', 'WAITING_RETRY', ?, ?, ?, ?, ?, 60, 3, ?)
                """, id, connection, phase, Timestamp.from(from), Timestamp.from(to),
                Timestamp.from(from), Timestamp.from(from.plusSeconds(3600)),
                Timestamp.from(from));
        return id;
    }

    private void finishJob(UUID jobId, String status, Instant finishedAt) {
        jdbc.update("UPDATE sync_jobs SET status = ?, finished_at = ? WHERE id = ?",
                status, Timestamp.from(finishedAt), jobId);
    }
}
