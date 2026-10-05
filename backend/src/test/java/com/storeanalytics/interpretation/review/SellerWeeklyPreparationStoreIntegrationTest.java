package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.SellerWeeklyPreparationStore.Claim;
import com.storeanalytics.interpretation.review.SellerWeeklyPreparationStore.Deferral;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SellerWeeklyPreparationStoreIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(1);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static SellerWeeklyPreparationStore repository;

    @BeforeAll
    static void initialize() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("95").load().migrate();
        jdbc = new JdbcTemplate(source);
        var fixture = new SellerWeeklyPreparationStoreIntegrationTest();
        UUID existingStore = fixture.store("UTC", "2026-09-07T00:00:00Z");
        UUID existingSnapshot = fixture.snapshot(existingStore, LocalDate.parse("2026-09-14"),
                "CURRENT_RANKING_AT_GENERATION");
        fixture.checkpoint(existingStore, LocalDate.parse("2026-09-14"), existingSnapshot, 0);
        List<String> before = jdbc.queryForList(
                "SELECT to_jsonb(store)::text FROM stores store ORDER BY id", String.class);
        List<String> preserved = retainedFacts();
        var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT to_jsonb(store)::text FROM stores store ORDER BY id", String.class))
                .isEqualTo(before);
        assertThat(retainedFacts()).isEqualTo(preserved);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller_weekly_preparation_jobs", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller_weekly_backlog_state", Long.class)).isZero();
        migration.validate();
        assertThat(migration.migrate().migrationsExecuted).isZero();
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        repository = new SellerWeeklyPreparationStore(jdbc, CLOCK);
        jdbc.update("UPDATE stores SET is_active = false WHERE id = ?", existingStore);
    }

    private static List<String> retainedFacts() {
        return jdbc.queryForList("""
                SELECT to_jsonb(fact)::text FROM store_seller_membership_state fact
                UNION ALL SELECT to_jsonb(fact)::text FROM weekly_review_snapshots fact
                UNION ALL SELECT to_jsonb(fact)::text FROM weekly_review_generation_state fact
                ORDER BY 1
                """, String.class);
    }

    @AfterEach
    void deactivateSyntheticStores() {
        jdbc.update("UPDATE stores SET is_active = false WHERE name = 'Backlog synthetic'");
    }

    @Test
    void migrationAndDiscoveryDoNotInferBaselineOrCreatePaidJobs() {
        UUID store = store("UTC", null);
        assertThat(discover(store, NOW, 52).reasonCode()).isEqualTo("BASELINE_OR_ACTIVE_STORE_MISSING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seller_weekly_backlog_state WHERE store_id = ?",
                Long.class, store)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_jobs job "
                + "JOIN weekly_review_snapshots snapshot ON snapshot.id = job.snapshot_id WHERE snapshot.store_id = ?",
                Long.class, store)).isZero();
    }

    @Test
    void boundedCursorSurvivesRestartAndFindsMissedWeeksAfterTwoBoundaries() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        assertThat(discover(store, NOW, 1).reasonCode()).isEqualTo("MORE_PAGES");
        var restarted = new SellerWeeklyPreparationStore(jdbc, CLOCK);
        assertThat(transaction.execute(status -> restarted.discover(store, NOW, 1)).insertedWeeks()).isEqualTo(1);
        assertThat(discover(store, NOW, 1).reasonCode()).isEqualTo("CAUGHT_UP");
        assertThat(weeks(store)).containsExactly(LocalDate.parse("2026-09-14"),
                LocalDate.parse("2026-09-21"), LocalDate.parse("2026-09-28"));
        assertThat(discover(store, NOW, 52).insertedWeeks()).isZero();
        assertThat(discover(store, NOW.plus(Duration.ofDays(14)), 52).insertedWeeks()).isEqualTo(2);
        assertThat(weeks(store)).hasSize(5);
    }

    @Test
    void midweekOrPartialMondayBaselineRequiresTwoFullyAuthoritativeWeeks() {
        UUID midweek = store("UTC", "2026-09-09T12:00:00Z");
        UUID partialMonday = store("UTC", "2026-09-07T00:00:00.000001Z");
        discover(midweek, NOW, 52);
        discover(partialMonday, NOW, 52);
        assertThat(weeks(midweek)).containsExactly(LocalDate.parse("2026-09-21"), LocalDate.parse("2026-09-28"));
        assertThat(weeks(partialMonday)).isEqualTo(weeks(midweek));
    }

    @Test
    void discoveryClosesAtLocalMidnightAndNotAtElapsedSevenDaysAcrossDst() {
        UUID store = store("Europe/Berlin", "2026-10-11T22:00:00Z");
        Instant close = Instant.parse("2026-10-25T23:00:00Z");
        assertThat(discover(store, close.minusNanos(1000), 52).insertedWeeks()).isZero();
        assertThat(discover(store, close, 52).insertedWeeks()).isEqualTo(1);
        assertThat(weeks(store)).containsExactly(LocalDate.parse("2026-10-19"));
    }

    @Test
    void configurationChangeRollsBackCursorAndDisablesClaimInsteadOfReinterpretingHistory() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        jdbc.update("UPDATE stores SET timezone = 'Europe/Kaliningrad' WHERE id = ?", store);
        assertThatThrownBy(() -> discover(store, NOW, 52)).hasMessage("BACKLOG_CONFIGURATION_CHANGED");
        assertThat(weeks(store)).hasSize(1);
        assertThat(claim(NOW)).isNull();
    }

    @Test
    void leaseTakeoverUsesNewTokenEvenWithSameOwnerAndRejectsStaleUpdates() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        Claim first = claim(NOW);
        assertThat(claim(NOW.plusSeconds(30))).isNull();
        Claim second = claim(NOW.plusSeconds(60));
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(heartbeat(first, NOW.plusSeconds(60))).isFalse();
        assertThat(defer(first, Deferral.FAILED, NOW.plusSeconds(60))).isFalse();
        assertThat(heartbeat(second, NOW.plusSeconds(70))).isTrue();
    }

    @Test
    void expiredLeaseCannotBeRevivedOrCompletedBeforeReclaim() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        Claim claim = claim(NOW);
        Instant expired = NOW.plusSeconds(60);
        assertThat(heartbeat(claim, expired)).isFalse();
        assertThat(defer(claim, Deferral.WAITING_HISTORY, expired)).isFalse();
        assertThat(complete(claim, UUID.randomUUID(), expired)).isFalse();
    }

    @Test
    void delayedSourceWaitDoesNotStarveOtherWeeksAndFailureDoesNotBusyRetry() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 2);
        Claim first = claim(NOW);
        assertThat(defer(first, Deferral.WAITING_SOURCES, NOW)).isTrue();
        Claim second = claim(NOW);
        assertThat(second.periodStart()).isEqualTo(first.periodStart().plusWeeks(1));
        assertThat(defer(second, Deferral.FAILED, NOW)).isTrue();
        assertThat(claim(NOW.plusSeconds(30))).isNull();
        assertThat(claim(NOW.plusSeconds(60)).id()).isEqualTo(first.id());
    }

    @Test
    void aDueOlderRetryDoesNotOvertakeAnUntouchedWeek() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 2);
        Claim older = claim(NOW);
        assertThat(defer(older, Deferral.WAITING_HISTORY, NOW)).isTrue();
        Claim next = claim(NOW.plusSeconds(61));
        assertThat(next.periodStart()).isEqualTo(older.periodStart().plusWeeks(1));
        assertThat(defer(next, Deferral.FAILED, NOW.plusSeconds(61))).isTrue();
        assertThat(claim(NOW.plusSeconds(61)).id()).isEqualTo(older.id());
    }

    @Test
    void lockedJobDoesNotBlockClaimOfAnotherWeek() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 2);
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var independent = new TransactionTemplate(new DataSourceTransactionManager(source));
        independent.setTimeout(5);
        transaction.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM seller_weekly_preparation_jobs WHERE store_id = ? "
                    + "ORDER BY period_start LIMIT 1 FOR UPDATE", UUID.class, store);
            var otherRepository = new SellerWeeklyPreparationStore(new JdbcTemplate(source), CLOCK);
            Claim selected = independent.execute(other -> otherRepository.claimNext("other", LEASE, NOW).orElseThrow());
            assertThat(selected.periodStart()).isEqualTo(LocalDate.parse("2026-09-21"));
        });
    }

    @Test
    void exactSnapshotBindingRejectsCurrentRosterWrongWeekAndOtherStore() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        Claim claim = claim(NOW);
        UUID current = snapshot(store, claim.periodStart(), "CURRENT_RANKING_AT_GENERATION");
        checkpoint(store, claim.periodStart(), current, 0);
        assertThat(complete(claim, current, NOW)).isFalse();
        UUID otherWeek = snapshot(store, claim.periodStart().plusWeeks(1), "HISTORICAL_DOCUMENT_MEMBERSHIP_V1");
        checkpoint(store, claim.periodStart().plusWeeks(1), otherWeek, 0);
        assertThat(complete(claim, otherWeek, NOW)).isFalse();
        UUID otherStore = store("UTC", "2026-09-07T00:00:00Z");
        UUID otherSnapshot = snapshot(otherStore, claim.periodStart(), "HISTORICAL_DOCUMENT_MEMBERSHIP_V1");
        checkpoint(otherStore, claim.periodStart(), otherSnapshot, 0);
        assertThat(complete(claim, otherSnapshot, NOW)).isFalse();
    }

    @Test
    void historicalSnapshotRequiresCurrentCheckpointAndLatestImmutableRevision() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        Claim claim = claim(NOW);
        UUID snapshot = snapshot(store, claim.periodStart(), "HISTORICAL_DOCUMENT_MEMBERSHIP_V1");
        checkpoint(store, claim.periodStart(), snapshot, 0);
        jdbc.update("UPDATE store_analytics_source_state SET revision = 1 WHERE store_id = ?", store);
        assertThat(complete(claim, snapshot, NOW)).isFalse();
        checkpoint(store, claim.periodStart(), snapshot, 1);
        assertThat(complete(claim, snapshot, NOW)).isTrue();
        assertThat(complete(claim, snapshot, NOW)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM seller_weekly_preparation_jobs WHERE id = ?",
                String.class, claim.id())).isEqualTo("SUCCEEDED");
    }

    @Test
    void supersededSnapshotCannotCompleteEvenWhenCheckpointStillNamesIt() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        Claim claim = claim(NOW);
        UUID snapshot = snapshot(store, claim.periodStart(), "HISTORICAL_DOCUMENT_MEMBERSHIP_V1");
        checkpoint(store, claim.periodStart(), snapshot, 0);
        UUID later = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_review_snapshots(id,store_id,period_start,period_end,timezone,revision,
                    supersedes_snapshot_id,report_contract_version,report_scope,metrics_policy_version,
                    snapshot_policy_version,quality_policy_version,report_state,report_payload,
                    content_hash,source_identity_hash)
                SELECT ?,store_id,period_start,period_end,timezone,2,id,report_contract_version,report_scope,
                    metrics_policy_version,snapshot_policy_version,quality_policy_version,report_state,
                    jsonb_set(report_payload,'{provenance}',
                        jsonb_build_object('snapshotPublicId',?::text,'revision',2)),content_hash,source_identity_hash
                FROM weekly_review_snapshots WHERE id = ?
                """, later, later.toString(), snapshot);
        assertThat(complete(claim, snapshot, NOW)).isFalse();
        checkpoint(store, claim.periodStart(), later, 0);
        assertThat(complete(claim, later, NOW)).isTrue();
    }

    @Test
    void bindingHoldsSourceFenceUntilCommitRatherThanOnlyComparingAnUnlockedRevision() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        Claim claim = claim(NOW);
        UUID snapshot = snapshot(store, claim.periodStart(), "HISTORICAL_DOCUMENT_MEMBERSHIP_V1");
        checkpoint(store, claim.periodStart(), snapshot, 0);
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var independent = new TransactionTemplate(new DataSourceTransactionManager(source));
        JdbcTemplate other = new JdbcTemplate(source);
        transaction.executeWithoutResult(status -> {
            assertThat(repository.completeWithSnapshot(claim, snapshot, NOW)).isTrue();
            assertThatThrownBy(() -> independent.executeWithoutResult(writer -> {
                other.execute("SET LOCAL lock_timeout = '100ms'");
                other.update("UPDATE store_analytics_source_state SET revision = revision + 1 WHERE store_id = ?",
                        store);
            })).isInstanceOf(org.springframework.dao.DataAccessException.class);
        });
        jdbc.update("UPDATE store_analytics_source_state SET revision = revision + 1 WHERE store_id = ?", store);
        assertThat(jdbc.queryForObject("SELECT revision FROM store_analytics_source_state WHERE store_id = ?",
                Long.class, store)).isEqualTo(1);
    }

    @Test
    void validatesBoundsAndSanitizedCodesBeforeWriting() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        assertThatThrownBy(() -> discover(store, NOW, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> discover(store, NOW, 53)).isInstanceOf(IllegalArgumentException.class);
        discover(store, NOW, 1);
        Claim claim = claim(NOW);
        assertThatThrownBy(() -> repository.defer(claim, Deferral.FAILED, "raw text", LEASE, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.claimNext("owner", Duration.ofDays(1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceChangeReopensSameFreeJobWithoutRewindingCursorOrCreatingPaidJob() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        Claim original = claim(NOW);
        UUID snapshot = snapshot(store, original.periodStart(), "HISTORICAL_DOCUMENT_MEMBERSHIP_V1");
        checkpoint(store, original.periodStart(), snapshot, 0);
        assertThat(complete(original, snapshot, NOW)).isTrue();
        assertThat(refresh(NOW)).isZero();
        jdbc.update("UPDATE store_analytics_source_state SET revision = 1 WHERE store_id = ?", store);
        assertThat(refresh(NOW)).isEqualTo(1);
        assertThat(refresh(NOW)).isZero();
        Claim reopened = claim(NOW);
        assertThat(reopened.id()).isEqualTo(original.id());
        assertThat(reopened.attemptCount()).isEqualTo(2);
        assertThat(weeks(store)).hasSize(1);
        assertThat(complete(reopened, snapshot, NOW)).isFalse();
        checkpoint(store, original.periodStart(), snapshot, 1);
        assertThat(complete(reopened, snapshot, NOW)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_jobs", Long.class)).isZero();
    }

    @Test
    void leaseValidationUsesFreshClockAfterLocksNotOnlyTheCallersOldTimestamp() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        Claim claim = claim(NOW);
        UUID snapshot = snapshot(store, claim.periodStart(), "HISTORICAL_DOCUMENT_MEMBERSHIP_V1");
        checkpoint(store, claim.periodStart(), snapshot, 0);
        Clock delayedClock = mock(Clock.class);
        when(delayedClock.instant()).thenReturn(NOW.plusSeconds(60));
        var delayed = new SellerWeeklyPreparationStore(jdbc, delayedClock);
        boolean renewed = transaction.execute(status -> delayed.heartbeat(claim, LEASE, NOW));
        boolean deferred = transaction.execute(status ->
                delayed.defer(claim, Deferral.FAILED, "SAFE_REASON", LEASE, NOW));
        boolean completed = transaction.execute(status -> delayed.completeWithSnapshot(claim, snapshot, NOW));
        assertThat(renewed).isFalse();
        assertThat(deferred).isFalse();
        assertThat(completed).isFalse();
    }

    @Test
    void weekBoundaryRequeuesExpiredTeamActionsWithoutAnySourceRevisionChange() {
        assertCalendarRefresh(true);
    }

    @Test
    void weekBoundaryRequeuesExpiredEmployeeActionWithoutTeamAction() {
        assertCalendarRefresh(false);
    }

    @Test
    void oldWeekWithoutFutureActionsDoesNotRefreshOnlyBecauseTheCalendarChanges() {
        UUID store = store("UTC", "2026-09-07T00:00:00Z");
        discover(store, NOW, 1);
        Claim claim = claim(NOW);
        UUID snapshot = snapshot(store, claim.periodStart(), "HISTORICAL_DOCUMENT_MEMBERSHIP_V1");
        checkpoint(store, claim.periodStart(), snapshot, 0);
        assertThat(complete(claim, snapshot, NOW)).isTrue();
        assertThat(refresh(NOW.plus(Duration.ofDays(7)))).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM seller_weekly_preparation_jobs WHERE id = ?",
                String.class, claim.id())).isEqualTo("SUCCEEDED");
    }

    private void assertCalendarRefresh(boolean teamAction) {
        UUID store = store("UTC", "2026-09-21T00:00:00Z");
        discover(store, NOW, 1);
        Claim claim = claim(NOW);
        assertThat(claim.periodStart()).isEqualTo(LocalDate.parse("2026-09-28"));
        UUID snapshot = snapshot(store, claim.periodStart(), "HISTORICAL_DOCUMENT_MEMBERSHIP_V1",
                teamAction ? "[{}]" : "[]", teamAction ? "[]" : "[{\"card\":{\"action\":{}}}]");
        checkpoint(store, claim.periodStart(), snapshot, 0);
        assertThat(complete(claim, snapshot, NOW)).isTrue();
        String payload = jdbc.queryForObject("SELECT report_payload::text FROM weekly_review_snapshots WHERE id = ?",
                String.class, snapshot);
        assertThat(refresh(NOW.plus(Duration.ofDays(6)))).isZero();
        assertThat(refresh(NOW.plus(Duration.ofDays(7)))).isOne();
        assertThat(refresh(NOW.plus(Duration.ofDays(7)))).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM store_analytics_source_state WHERE store_id = ?",
                Long.class, store)).isZero();
        assertThat(jdbc.queryForObject("SELECT report_payload::text FROM weekly_review_snapshots WHERE id = ?",
                String.class, snapshot)).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT status FROM seller_weekly_preparation_jobs WHERE id = ?",
                String.class, claim.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT last_reason_code FROM seller_weekly_preparation_jobs WHERE id = ?",
                String.class, claim.id())).isEqualTo("SNAPSHOT_NOT_CURRENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_jobs", Long.class)).isZero();
    }

    private SellerWeeklyPreparationStore.Discovery discover(UUID store, Instant now, int maximum) {
        return transaction.execute(status -> repository.discover(store, now, maximum));
    }

    private Claim claim(Instant now) {
        return transaction.execute(status -> repository.claimNext("worker", LEASE, now).orElse(null));
    }

    private boolean defer(Claim claim, Deferral reason, Instant now) {
        return transaction.execute(status -> repository.defer(claim, reason, "SAFE_REASON", LEASE, now));
    }

    private boolean heartbeat(Claim claim, Instant now) {
        return transaction.execute(status -> repository.heartbeat(claim, LEASE, now));
    }

    private int refresh(Instant now) {
        return transaction.execute(status -> repository.requeueStaleSnapshots(1, now));
    }

    private boolean complete(Claim claim, UUID snapshot, Instant now) {
        return transaction.execute(status -> repository.completeWithSnapshot(claim, snapshot, now));
    }

    private List<LocalDate> weeks(UUID store) {
        return jdbc.queryForList("SELECT period_start FROM seller_weekly_preparation_jobs WHERE store_id = ? "
                + "ORDER BY period_start", LocalDate.class, store);
    }

    private UUID store(String timezone, String baseline) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO stores(id,connection_id,source_system,external_id,name,timezone)
                SELECT ?,id,'LIVESKLAD',?,'Backlog synthetic',? FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """, id, id.toString(), timezone);
        if (baseline != null) {
            jdbc.update("""
                    INSERT INTO store_seller_membership_state(store_id,authoritative_from,baseline_source,
                        baseline_recorded_at) VALUES (?,?,'TEST',?)
                    """, id, Timestamp.from(Instant.parse(baseline)), Timestamp.from(Instant.parse(baseline)));
        }
        return id;
    }

    private UUID snapshot(UUID store, LocalDate start, String basis) {
        return snapshot(store, start, basis, "[]", "[]");
    }

    private UUID snapshot(UUID store, LocalDate start, String basis, String actions, String employees) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_review_snapshots(id,store_id,period_start,period_end,timezone,revision,
                    report_contract_version,report_scope,metrics_policy_version,snapshot_policy_version,
                    quality_policy_version,report_state,report_payload,content_hash,source_identity_hash)
                VALUES (?,?,?,?,'UTC',1,3,'SELLERS','test','test','test','READY',
                    jsonb_build_object('contractVersion',3,'scope','SELLERS','reportState','READY',
                        'versions',jsonb_build_object(
                            'metricsPolicy','test','snapshotPolicy','test','qualityPolicy','test'),
                        'period',jsonb_build_object('timezone','UTC',
                            'current',jsonb_build_object('start',?::text,'end',?::text)),
                        'provenance',jsonb_build_object('snapshotPublicId',?::text,'revision',1),'sourceIdentityHash',?,
                        'membership',jsonb_build_object('basis',?,
                            'currentCohortHash',repeat('c',64),'previousCohortHash',repeat('c',64)),
                        'actions',?::jsonb,'employees',?::jsonb),?,?)
                """, id, store, start, start.plusDays(6), start.toString(), start.plusDays(6).toString(), id.toString(),
                "a".repeat(64), basis, actions, employees, "b".repeat(64), "a".repeat(64));
        return id;
    }

    private void checkpoint(UUID store, LocalDate start, UUID snapshot, long revision) {
        jdbc.update("INSERT INTO store_analytics_source_state(store_id,revision) VALUES (?,?) ON CONFLICT DO NOTHING",
                store, revision);
        jdbc.update("""
                INSERT INTO weekly_review_generation_state(store_id,period_start,period_end,target_contract_version,
                    last_evaluated_source_identity_hash,last_evaluated_source_revision,compatible_snapshot_id,
                    evaluated_at,outcome) VALUES (?,?,?,3,?,?,?,?,'CREATED')
                ON CONFLICT (store_id,period_start,period_end,target_contract_version) DO UPDATE SET
                    compatible_snapshot_id = EXCLUDED.compatible_snapshot_id,
                    last_evaluated_source_revision = EXCLUDED.last_evaluated_source_revision
                """, store, start, start.plusDays(6), "a".repeat(64), revision, snapshot, Timestamp.from(NOW));
    }
}
