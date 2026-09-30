package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.storeanalytics.interpretation.review.WeeklyReviewFacts.PeriodFacts;
import com.storeanalytics.interpretation.review.WeeklyReviewPolicyV1.RevenuePeriod;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.DateRange;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.PeriodContext;
import com.storeanalytics.interpretation.review.WeeklyReviewResponse.Provenance;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response.AdditionalSales;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response.Membership;
import com.storeanalytics.interpretation.review.WeeklyReviewV3Response.TeamDisplay;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiOperatorService;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiPreflightView;
import com.storeanalytics.interpretation.snapshot.EmployeeSalesSampleFacts;
import com.storeanalytics.metrics.service.AttachRateDataQuality;
import com.storeanalytics.metrics.service.AttachRateResult;
import com.storeanalytics.metrics.service.CategoryKpiDataQuality;
import com.storeanalytics.metrics.service.CategoryKpiGroup;
import com.storeanalytics.metrics.service.CategoryKpiMetrics;
import com.storeanalytics.metrics.service.CategoryKpiResult;
import com.storeanalytics.metrics.service.SellerCohortSnapshot;
import com.storeanalytics.metrics.service.SellerPeriodComparisonFacts;
import com.storeanalytics.metrics.service.SellerPeriodFacts;
import com.storeanalytics.metrics.service.SellerPeriodMetrics;
import com.storeanalytics.metrics.service.SellerReturnAttributionQuality;
import com.storeanalytics.metrics.service.StoreKpiDataQuality;
import com.storeanalytics.metrics.service.StoreKpiResult;
import com.storeanalytics.performance.service.EmployeeRatingResult;
import com.storeanalytics.store.service.StoreDataFreshnessStatus;
import com.storeanalytics.store.service.StoreDataStatusView;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class WeeklyReviewSnapshotStoreIntegrationTest {

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock weeklySnapshotTestClock() {
            return Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), ZoneOffset.UTC);
        }
    }

    private static final DateRange CURRENT = new DateRange(
            LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 23)
    );
    private static final DateRange PREVIOUS = new DateRange(
            LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16)
    );
    private static final PeriodContext PERIOD = new PeriodContext(
            "Europe/Kaliningrad",
            CURRENT,
            PREVIOUS,
            "17–23 августа 2026",
            "10–16 августа 2026"
    );

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklyReviewSnapshotStore store;

    @Autowired
    private SellerWeeklySourceRevisionRepository sourceRevisions;

    @Autowired
    private SellerWeeklyV3CandidateService sellerCandidates;

    @Autowired
    private SellerWeeklyV3ReadService sellerReads;

    @Autowired
    private SellerWeeklyV3PlanningService sellerPlanner;

    @Autowired
    private SellerWeeklyReviewFactsSource sellerFacts;

    @Autowired
    private SellerWeeklyIdentityFactsSource sellerIdentityFacts;

    @Autowired
    private SellerWeeklySourceIdentity sellerIdentity;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private WeeklyReviewAiOperatorService aiOperatorService;

    @Autowired
    private WeeklyReviewV3SnapshotCodec v3Codec;

    @Autowired
    private WeeklyReviewAttributionRepository attribution;

    @Autowired
    private WeeklyReviewFactsSource legacyFacts;

    @Autowired
    private WeeklyReviewSnapshotCodec v2Codec;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.llm.yandex.folder-id", () -> "preflight-folder");
        registry.add(
                "app.llm.yandex.model-uri",
                () -> "gpt://preflight-folder/yandexgpt-5.1"
        );
    }

    @Test
    void reusesEqualContentCreatesRevisionForChangesAndRejectsMutation() {
        UUID storeId = addStore();
        WeeklyReviewFacts zero = facts(storeId, "0.00", "0.00");

        PersistedWeeklyReviewSnapshot first = store.persist(
                zero, Instant.parse("2026-08-24T04:00:00Z")
        );
        PersistedWeeklyReviewSnapshot reused = store.persist(
                zero, Instant.parse("2026-08-24T04:05:00Z")
        );
        PersistedWeeklyReviewSnapshot revision = store.persist(
                facts(storeId, "100.00", "0.00"),
                Instant.parse("2026-08-24T04:10:00Z")
        );

        assertThat(first.revision()).isOne();
        assertThat(reused.id()).isEqualTo(first.id());
        assertThat(reused.revision()).isOne();
        assertThat(revision.revision()).isEqualTo(2);
        assertThat(revision.supersedesSnapshotId()).isEqualTo(first.id());
        assertThat(revision.response().provenance().revisionChanged()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_snapshots WHERE store_id = ?",
                Long.class,
                storeId
        )).isEqualTo(2L);
        assertThat(store.findLatest(storeId, CURRENT))
                .get()
                .extracting(PersistedWeeklyReviewSnapshot::id)
                .isEqualTo(revision.id());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE weekly_review_snapshots SET report_state = 'READY' WHERE id = ?",
                revision.id()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Weekly review snapshots are immutable");
    }

    @Test
    void lightweightIdentityMatchesFullFactsForAttachV3AndEmptyRoster() {
        UUID storeId = addStore();
        Instant calculated = Instant.parse("2026-08-24T04:00:00Z");
        var full = sellerFacts.load(storeId, calculated, PERIOD.timezone());
        var metadata = sellerIdentityFacts.load(storeId, calculated, PERIOD.timezone());

        assertThat(full.comparison().current().attachFormulaVersion()).isEqualTo("attach-rate-v3");
        assertThat(full.comparison().current().metrics().cohort().employeeIds()).isEmpty();
        assertThat(sellerIdentity.hash(metadata)).isEqualTo(sellerIdentity.hash(full));
    }

    @Test
    void attributionMarkerBeforeApplicationCalculationTimeStillInvalidatesSnapshot() {
        UUID storeId = addStore();
        Instant calculated = Instant.parse("2026-08-24T04:00:00Z");
        var snapshot = store.persist(facts(storeId, "0.00", "0.00"), calculated);
        jdbcTemplate.update("INSERT INTO attach_attribution_changes (store_id, changed_at) VALUES (?, ?)",
                storeId, Timestamp.from(calculated.minusSeconds(60)));

        assertThat(store.attributionChangedSince(storeId, snapshot.id(), calculated)).isTrue();
    }

    @Test
    void attributionAcknowledgesExactObservedMarkerRegardlessOfClockDirection() {
        UUID storeId = addStore();
        Instant calculated = Instant.parse("2026-08-24T04:00:00Z");
        var snapshot = store.persist(facts(storeId, "0.00", "0.00"), calculated);
        for (long offset : List.of(60L, -60L)) {
            Instant marker = calculated.plusSeconds(offset);
            changeAttribution(storeId, marker);
            assertThat(store.attributionChangedSince(storeId, snapshot.id(), calculated)).isTrue();
            var observed = attribution.observe(storeId);
            assertThat(observed).contains(marker);
            store.acknowledgeAttribution(storeId, snapshot.id(), observed);
            assertThat(store.attributionChangedSince(storeId, snapshot.id(), calculated)).isFalse();
        }
    }

    @Test
    void oldWallClockAcknowledgementIsNotTrustedAndUnchangedContentIsReused() {
        UUID storeId = addStore();
        Instant calculated = Instant.parse("2026-08-24T04:00:00Z");
        var snapshot = store.persist(facts(storeId, "0.00", "0.00"), calculated);
        jdbcTemplate.update("INSERT INTO attach_snapshot_checks (store_id, snapshot_id, checked_through) "
                + "VALUES (?, ?, ?)", storeId, snapshot.id(), Timestamp.from(calculated.plusSeconds(120)));
        changeAttribution(storeId, calculated.minusSeconds(60));

        assertThat(store.attributionChangedSince(storeId, snapshot.id(), calculated)).isTrue();
        var reused = store.persist(facts(storeId, "0.00", "0.00"), calculated.plusSeconds(10));
        assertThat(reused.id()).isEqualTo(snapshot.id());
        store.acknowledgeAttribution(storeId, reused.id(), attribution.observe(storeId));
        assertThat(store.attributionChangedSince(storeId, reused.id(), calculated)).isFalse();
    }

    @Test
    void changeAfterFactsReadRemainsUnacknowledgedUntilNextRead() {
        UUID storeId = addStore();
        Instant calculated = Instant.parse("2026-08-24T04:00:00Z");
        changeAttribution(storeId, calculated.minusSeconds(60));
        var captured = legacyFacts.loadForGeneration(storeId, calculated, PERIOD.timezone());
        assertThat(captured.attributionChange()).contains(calculated.minusSeconds(60));
        changeAttribution(storeId, calculated.minusSeconds(120));
        var snapshot = store.persist(captured.facts(), calculated);
        store.acknowledgeAttribution(storeId, snapshot.id(), captured.attributionChange());

        assertThat(store.attributionChangedSince(storeId, snapshot.id(), calculated)).isTrue();
        var refreshed = legacyFacts.loadForGeneration(storeId, calculated, PERIOD.timezone());
        var reused = store.persist(refreshed.facts(), calculated);
        assertThat(reused.id()).isEqualTo(snapshot.id());
        store.acknowledgeAttribution(storeId, reused.id(), refreshed.attributionChange());
        assertThat(store.attributionChangedSince(storeId, reused.id(), calculated)).isFalse();
    }

    @Test
    void absentMarkerDoesNotHideFirstDecisionAndStoresRemainIsolated() {
        UUID storeId = addStore();
        UUID otherStore = addStore();
        Instant calculated = Instant.parse("2026-08-24T04:00:00Z");
        var captured = legacyFacts.loadForGeneration(storeId, calculated, PERIOD.timezone());
        assertThat(captured.attributionChange()).isEmpty();
        var snapshot = store.persist(captured.facts(), calculated);
        store.acknowledgeAttribution(storeId, snapshot.id(), captured.attributionChange());
        assertThat(store.attributionChangedSince(storeId, snapshot.id(), calculated)).isFalse();
        changeAttribution(otherStore, calculated.minusSeconds(60));
        assertThat(store.attributionChangedSince(storeId, snapshot.id(), calculated)).isFalse();
        changeAttribution(storeId, calculated.minusSeconds(60));
        assertThat(store.attributionChangedSince(storeId, snapshot.id(), calculated)).isTrue();
    }

    private void changeAttribution(UUID storeId, Instant marker) {
        jdbcTemplate.update("""
                INSERT INTO attach_attribution_changes (store_id, changed_at) VALUES (?, ?)
                ON CONFLICT (store_id) DO UPDATE SET changed_at = EXCLUDED.changed_at
                """, storeId, Timestamp.from(marker));
    }

    @Test
    void legacyReadSkipsV3AndRollbackKeepsOneRevisionChain() {
        UUID storeId = addStore();
        WeeklyReviewFacts unchanged = facts(storeId, "0.00", "0.00");
        PersistedWeeklyReviewSnapshot first = store.persist(
                unchanged, Instant.parse("2026-08-24T04:00:00Z"));
        UUID syntheticV3Id = UUID.randomUUID();
        WeeklyReviewV3Response syntheticV3 = insertSyntheticV3(
                first.id(), syntheticV3Id, "SELLERS", null);

        assertThat(store.findLatest(storeId, CURRENT)).get()
                .extracting(PersistedWeeklyReviewSnapshot::id).isEqualTo(first.id());
        assertThat(store.findById(syntheticV3Id)).isEmpty();
        assertThat(store.findLatestV3(storeId, CURRENT)).get()
                .extracting(PersistedWeeklyReviewV3Snapshot::response).isEqualTo(syntheticV3);
        assertThat(store.findV3ById(syntheticV3Id)).get()
                .extracting(PersistedWeeklyReviewV3Snapshot::contentHash)
                .isEqualTo(v3Codec.contentHash(syntheticV3));

        PersistedWeeklyReviewSnapshot rollback = store.persist(
                unchanged, Instant.parse("2026-08-24T04:10:00Z"));
        assertThat(rollback.revision()).isEqualTo(3);
        assertThat(rollback.supersedesSnapshotId()).isEqualTo(syntheticV3Id);
        assertThat(rollback.contentHash()).isEqualTo(first.contentHash());
        assertThat(rollback.id()).isNotEqualTo(first.id());
        assertThat(store.findLatest(storeId, CURRENT)).get()
                .extracting(PersistedWeeklyReviewSnapshot::id).isEqualTo(rollback.id());
        assertThat(store.findLatestV3(storeId, CURRENT)).get()
                .extracting(PersistedWeeklyReviewV3Snapshot::id).isEqualTo(syntheticV3Id);
    }

    @Test
    void internalSellerWriterPreservesV2V3RollbackChainAndReusesUnchangedContent() {
        UUID storeId = addStore();
        WeeklyReviewFacts legacyFacts = facts(storeId, "0.00", "0.00");
        PersistedWeeklyReviewSnapshot first = store.persist(
                legacyFacts, Instant.parse("2026-08-24T04:00:00Z"));
        SellerWeeklyReviewFacts sellerFacts = blockedSellerFacts(storeId);
        String identity = "a".repeat(64);

        PersistedWeeklyReviewV3Snapshot second = store.persistV3Candidate(sellerFacts,
                Instant.parse("2026-08-24T04:05:00Z"), identity);
        PersistedWeeklyReviewV3Snapshot reused = store.persistV3Candidate(sellerFacts,
                Instant.parse("2026-08-24T04:06:00Z"), "b".repeat(64));
        Map<String, Object> reusedCheckpoint = jdbcTemplate.queryForMap("""
                SELECT last_evaluated_source_identity_hash, compatible_snapshot_id, outcome
                FROM weekly_review_generation_state WHERE store_id = ?
                """, storeId);
        PersistedWeeklyReviewSnapshot rollback = store.persist(
                legacyFacts, Instant.parse("2026-08-24T04:10:00Z"));
        PersistedWeeklyReviewV3Snapshot fourth = store.persistV3Candidate(sellerFacts,
                Instant.parse("2026-08-24T04:15:00Z"), identity);

        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.supersedesSnapshotId()).isEqualTo(first.id());
        assertThat(second.response().scope()).isEqualTo("SELLERS");
        assertThat(second.response().reportState()).isEqualTo(WeeklyReviewResponse.ReportState.BLOCKED);
        assertThat(second.response().results()).allSatisfy(item -> assertThat(item.current()).isNull());
        assertThat(reused.id()).isEqualTo(second.id());
        assertThat(reusedCheckpoint.get("last_evaluated_source_identity_hash"))
                .isEqualTo("b".repeat(64));
        assertThat(reusedCheckpoint.get("compatible_snapshot_id")).isEqualTo(second.id());
        assertThat(reusedCheckpoint.get("outcome")).isEqualTo("REUSED");
        assertThat(rollback.revision()).isEqualTo(3);
        assertThat(rollback.supersedesSnapshotId()).isEqualTo(second.id());
        assertThat(fourth.revision()).isEqualTo(4);
        assertThat(fourth.supersedesSnapshotId()).isEqualTo(rollback.id());
        assertThat(fourth.contentHash()).isEqualTo(second.contentHash());
        Map<String, Object> createdCheckpoint = jdbcTemplate.queryForMap("""
                SELECT last_evaluated_source_identity_hash, compatible_snapshot_id, outcome
                FROM weekly_review_generation_state WHERE store_id = ?
                """, storeId);
        assertThat(createdCheckpoint.get("last_evaluated_source_identity_hash"))
                .isEqualTo(identity);
        assertThat(createdCheckpoint.get("compatible_snapshot_id")).isEqualTo(fourth.id());
        assertThat(createdCheckpoint.get("outcome")).isEqualTo("CREATED");
        assertThat(store.findLatest(storeId, CURRENT)).get()
                .extracting(PersistedWeeklyReviewSnapshot::id).isEqualTo(rollback.id());
        assertThat(store.findLatestV3(storeId, CURRENT)).get()
                .extracting(PersistedWeeklyReviewV3Snapshot::id).isEqualTo(fourth.id());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM weekly_review_snapshots WHERE store_id = ?
                """, Long.class, storeId)).isEqualTo(4L);
    }

    @Test
    void internalSellerReadDistinguishesCurrentRollbackAndSourceChange() {
        UUID storeId = addStore();
        PersistedWeeklyReviewV3Snapshot first = sellerCandidates.generateCandidate(
                storeId, PERIOD.timezone());

        SellerWeeklyV3ReadResult current = sellerReads.assessForPlanning(storeId);
        assertThat(current.state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        assertThat(current.snapshot()).contains(first);

        store.persist(facts(storeId, "0.00", "0.00"),
                Instant.parse("2026-08-24T04:10:00Z"));
        SellerWeeklyV3ReadResult rolledBack = sellerReads.assessForPlanning(storeId);
        assertThat(rolledBack.state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        assertThat(rolledBack.snapshot()).contains(first);

        PersistedWeeklyReviewV3Snapshot restored = sellerCandidates.generateCandidate(
                storeId, PERIOD.timezone());
        assertThat(restored.revision()).isEqualTo(first.revision() + 2);
        assertThat(sellerReads.assessForPlanning(storeId).state())
                .isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);

        jdbcTemplate.update("UPDATE stores SET timezone = 'Europe/Moscow' WHERE id = ?", storeId);
        SellerWeeklyV3ReadResult stale = sellerReads.assessForPlanning(storeId);
        assertThat(stale.state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        assertThat(stale.snapshot()).contains(restored);
    }

    @Test
    void internalSellerPlannerDefersMissingCoverageWithoutCreatingDurableState() {
        UUID storeId = addStore();

        SellerWeeklyV3PlanningResult result = sellerPlanner.evaluate(storeId);

        assertThat(result.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        assertThat(result.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.PREPARING);
        assertThat(store.findLatestV3(storeId, CURRENT)).isEmpty();
        assertThat(store.findV3GenerationState(storeId, CURRENT)).isEmpty();
    }

    @Test
    void internalSellerPlannerKeepsVerifiedSnapshotUntilSuccessfulReconciliation() {
        UUID storeId = addStore();
        Instant initialFinish = Instant.parse("2026-08-24T03:00:00Z");
        for (String scope : List.of("SALES", "RETURNS", "ORDERS")) {
            addSellerCoverageRun(storeId, scope, "SUCCESS", initialFinish);
        }
        SellerWeeklyV3PlanningResult first = sellerPlanner.evaluate(storeId);
        assertThat(first.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        assertThat(first.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        var checkpoint = store.findV3GenerationState(storeId, CURRENT).orElseThrow();

        assertThat(sellerPlanner.evaluate(storeId).outcome())
                .isEqualTo(SellerWeeklyV3PlanningResult.Outcome.UNCHANGED);
        assertThat(store.findV3GenerationState(storeId, CURRENT)).contains(checkpoint);

        UUID active = addSellerCoverageRun(storeId, "RETURNS", "RUNNING", null);
        SellerWeeklyV3PlanningResult syncing = sellerPlanner.evaluate(storeId);
        assertThat(syncing.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        assertThat(syncing.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.STALE);
        assertThat(syncing.review().snapshot()).isEqualTo(first.review().snapshot());
        assertThat(store.findV3GenerationState(storeId, CURRENT)).contains(checkpoint);

        jdbcTemplate.update("UPDATE sync_runs SET status = 'FAILED', finished_at = ? WHERE id = ?",
                Timestamp.from(initialFinish.plusSeconds(120)), active);
        SellerWeeklyV3PlanningResult failed = sellerPlanner.evaluate(storeId);
        assertThat(failed.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        assertThat(failed.review().snapshot()).isEqualTo(first.review().snapshot());
        assertThat(store.findV3GenerationState(storeId, CURRENT)).contains(checkpoint);

        jdbcTemplate.update("UPDATE sync_runs SET status = 'CANCELLED' WHERE id = ?", active);
        SellerWeeklyV3PlanningResult cancelled = sellerPlanner.evaluate(storeId);
        assertThat(cancelled.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.DEFERRED);
        assertThat(cancelled.review().snapshot()).isEqualTo(first.review().snapshot());
        assertThat(store.findV3GenerationState(storeId, CURRENT)).contains(checkpoint);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_snapshots WHERE store_id = ?", Long.class, storeId))
                .isOne();

        addSellerCoverageRun(storeId, "RETURNS", "SUCCESS", initialFinish.plusSeconds(240));
        SellerWeeklyV3PlanningResult reconciled = sellerPlanner.evaluate(storeId);
        assertThat(reconciled.outcome()).isEqualTo(SellerWeeklyV3PlanningResult.Outcome.EVALUATED);
        assertThat(reconciled.review().state()).isEqualTo(SellerWeeklyV3ReadResult.State.CURRENT);
        var reconciledCheckpoint = store.findV3GenerationState(storeId, CURRENT).orElseThrow();
        assertThat(reconciledCheckpoint.sourceRevision()).isEqualTo(sourceRevisions.read(storeId));
        assertThat(reconciledCheckpoint.outcome()).isEqualTo("REUSED");
        assertThat(reconciled.review().snapshot()).isEqualTo(first.review().snapshot());

        assertThat(sellerPlanner.evaluate(storeId).outcome())
                .isEqualTo(SellerWeeklyV3PlanningResult.Outcome.UNCHANGED);
        assertThat(store.findV3GenerationState(storeId, CURRENT)).contains(reconciledCheckpoint);
    }

    @Test
    void internalSellerPlannerRejectsAnEnclosingTransaction() {
        UUID storeId = addStore();
        TransactionTemplate outer = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> sellerPlanner.evaluate(storeId)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(store.findV3GenerationState(storeId, CURRENT)).isEmpty();
    }

    private UUID addSellerCoverageRun(UUID storeId, String scope, String status, Instant finishedAt) {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT connection_id FROM stores WHERE id = ?", UUID.class, storeId);
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sync_runs
                    (id, connection_id, store_id, source_system, trigger_type, sync_scope, status,
                     period_start, period_end, started_at, finished_at)
                VALUES (?, ?, ?, 'LIVESKLAD', 'MANUAL', ?, ?, ?, ?, ?, ?)
                """, runId, connectionId, storeId, scope, status,
                Timestamp.from(Instant.parse("2026-08-09T22:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-23T22:00:00Z")),
                Timestamp.from(Instant.parse("2026-08-24T02:00:00Z")),
                finishedAt == null ? null : Timestamp.from(finishedAt));
        return runId;
    }

    @Test
    void sellerWriterRejectsOldTimezoneEvenWithMatchingSourceRevision() {
        UUID storeId = addStore();
        jdbcTemplate.update("UPDATE stores SET timezone = 'Europe/Moscow' WHERE id = ?", storeId);
        SellerWeeklyReviewFacts wrongTimezone = sellerFacts.load(
                storeId, Instant.parse("2026-08-24T04:00:00Z"), PERIOD.timezone());
        assertThat(wrongTimezone.sourceRevision()).isEqualTo(sourceRevisions.read(storeId));

        assertThatThrownBy(() -> store.persistV3Candidate(wrongTimezone,
                Instant.parse("2026-08-24T04:05:00Z"), "a".repeat(64)))
                .isInstanceOf(SellerWeeklySourceChangedException.class);
        assertThat(store.findLatestV3(storeId, CURRENT)).isEmpty();
        assertThat(store.findV3GenerationState(storeId, CURRENT)).isEmpty();
    }

    @Test
    void sellerWriterRejectsMembershipChangeAfterFactsReadWithoutInsertingSnapshot() {
        UUID storeId = addStore();
        SellerWeeklyReviewFacts facts = blockedSellerFacts(storeId);
        UUID employeeId = UUID.randomUUID();
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT connection_id FROM stores WHERE id = ?", UUID.class, storeId);
        jdbcTemplate.update("""
                INSERT INTO employees (id, connection_id, external_id, full_name)
                VALUES (?, ?, ?, 'Synthetic seller')
                """, employeeId, connectionId, employeeId.toString());
        jdbcTemplate.update("""
                INSERT INTO employee_store_assignments
                    (employee_id, store_id, is_active, participates_in_ranking)
                VALUES (?, ?, true, true)
                """, employeeId, storeId);
        assertThat(sourceRevisions.read(storeId)).isEqualTo(1);

        assertThatThrownBy(() -> store.persistV3Candidate(facts,
                Instant.parse("2026-08-24T04:05:00Z"), "a".repeat(64)))
                .isInstanceOf(SellerWeeklySourceChangedException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_snapshots WHERE store_id = ?",
                Long.class, storeId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_generation_state WHERE store_id = ?",
                Long.class, storeId)).isZero();

        when(facts.sourceRevision()).thenReturn(1L);
        PersistedWeeklyReviewV3Snapshot accepted = store.persistV3Candidate(
                facts, Instant.parse("2026-08-24T04:06:00Z"), "a".repeat(64));
        assertThat(accepted.revision()).isOne();

        jdbcTemplate.update("""
                UPDATE employee_store_assignments SET participates_in_ranking = false
                WHERE store_id = ? AND employee_id = ?
                """, storeId, employeeId);
        assertThat(sourceRevisions.read(storeId)).isEqualTo(2);
        assertThatThrownBy(() -> store.persistV3Candidate(facts,
                Instant.parse("2026-08-24T04:07:00Z"), "a".repeat(64)))
                .isInstanceOf(SellerWeeklySourceChangedException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_snapshots WHERE store_id = ?",
                Long.class, storeId)).isOne();
    }

    @Test
    void sellerWriterRejectsFactsFromPreviousLocalDayWithoutDatabaseChanges() {
        UUID storeId = addStore();
        SellerWeeklyReviewFacts facts = blockedSellerFacts(storeId);
        when(facts.sourceDataStatus().expectedThroughDate())
                .thenReturn(CURRENT.end().minusDays(1));

        assertThatThrownBy(() -> store.persistV3Candidate(facts,
                Instant.parse("2026-08-24T04:05:00Z"), "a".repeat(64)))
                .isInstanceOf(SellerWeeklySourceChangedException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_snapshots WHERE store_id = ?",
                Long.class, storeId)).isZero();
    }

    @Test
    void sourceChangesCoalescePerStoreTransactionAndDoNotPreventStoreDeletion() {
        UUID storeId = addStore();
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbcTemplate.update("UPDATE stores SET timezone = 'Europe/Berlin' WHERE id = ?", storeId);
            jdbcTemplate.update("UPDATE stores SET timezone = 'Europe/Moscow' WHERE id = ?", storeId);
        });
        assertThat(sourceRevisions.read(storeId)).isOne();
        jdbcTemplate.update("UPDATE stores SET name = 'Renamed store' WHERE id = ?", storeId);
        assertThat(sourceRevisions.read(storeId)).isOne();

        UUID employeeId = UUID.randomUUID();
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT connection_id FROM stores WHERE id = ?", UUID.class, storeId);
        jdbcTemplate.update("""
                INSERT INTO employees (id, connection_id, external_id, full_name)
                VALUES (?, ?, ?, 'Synthetic seller')
                """, employeeId, connectionId, employeeId.toString());
        jdbcTemplate.update("""
                INSERT INTO employee_store_assignments (employee_id, store_id)
                VALUES (?, ?)
                """, employeeId, storeId);
        jdbcTemplate.update("DELETE FROM stores WHERE id = ?", storeId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stores WHERE id = ?", Long.class, storeId)).isZero();
    }

    @Test
    void sourceRevisionTracksSyncCoverageAndSalesButNotProgressCounters() {
        UUID storeId = addStore();
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT connection_id FROM stores WHERE id = ?", UUID.class, storeId);
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sync_runs
                    (id, connection_id, store_id, source_system, trigger_type, sync_scope, status)
                VALUES (?, ?, ?, 'LIVESKLAD', 'MANUAL', 'SALES', 'RUNNING')
                """, runId, connectionId, storeId);
        assertThat(sourceRevisions.read(storeId)).isOne();
        jdbcTemplate.update("UPDATE sync_runs SET records_fetched = 1 WHERE id = ?", runId);
        assertThat(sourceRevisions.read(storeId)).isOne();
        jdbcTemplate.update("""
                UPDATE sync_runs SET status = 'SUCCESS', finished_at = now() WHERE id = ?
                """, runId);
        assertThat(sourceRevisions.read(storeId)).isEqualTo(2);

        UUID documentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO sales_documents
                    (id, connection_id, external_id, store_id, document_kind, source_document_type,
                     occurred_at, business_date, net_amount, cost_amount, last_sync_run_id)
                VALUES (?, ?, ?, ?, 'SALE', 'sale', now(), '2026-08-20', 100, 50, ?)
                """, documentId, connectionId, documentId.toString(), storeId, runId);
        assertThat(sourceRevisions.read(storeId)).isEqualTo(3);
        jdbcTemplate.update("UPDATE sales_documents SET net_amount = 120 WHERE id = ?", documentId);
        assertThat(sourceRevisions.read(storeId)).isEqualTo(4);
    }

    @Test
    void sourceChangeDuringSnapshotInsertCommitsAfterFencedSnapshot() throws Exception {
        UUID storeId = addStore();
        UUID employeeId = UUID.randomUUID();
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT connection_id FROM stores WHERE id = ?", UUID.class, storeId);
        jdbcTemplate.update("""
                INSERT INTO employees (id, connection_id, external_id, full_name)
                VALUES (?, ?, ?, 'Synthetic seller')
                """, employeeId, connectionId, employeeId.toString());
        jdbcTemplate.update("""
                INSERT INTO employee_store_assignments
                    (employee_id, store_id, is_active, participates_in_ranking)
                VALUES (?, ?, true, true)
                """, employeeId, storeId);
        SellerWeeklyReviewFacts facts = blockedSellerFacts(storeId);
        when(facts.sourceRevision()).thenReturn(sourceRevisions.read(storeId));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                blocker.createStatement().execute(
                        "LOCK TABLE weekly_review_snapshots IN ACCESS EXCLUSIVE MODE");
                Future<PersistedWeeklyReviewV3Snapshot> snapshot = workers.submit(() ->
                        store.persistV3Candidate(facts, Instant.parse("2026-08-24T04:05:00Z"),
                                "a".repeat(64)));
                awaitBlockedSql("weekly_review_snapshots");
                Future<Integer> change = workers.submit(() -> jdbcTemplate.update("""
                        UPDATE employee_store_assignments SET participates_in_ranking = false
                        WHERE store_id = ? AND employee_id = ?
                        """, storeId, employeeId));
                awaitBlockedSql("UPDATE employee_store_assignments");
                blocker.commit();

                assertThat(snapshot.get(10, TimeUnit.SECONDS).revision()).isOne();
                assertThat(change.get(10, TimeUnit.SECONDS)).isOne();
                assertThat(sourceRevisions.read(storeId)).isEqualTo(facts.sourceRevision() + 1);
                assertThatThrownBy(() -> store.persistV3Candidate(facts,
                        Instant.parse("2026-08-24T04:06:00Z"), "a".repeat(64)))
                        .isInstanceOf(SellerWeeklySourceChangedException.class);
            } finally {
                blocker.rollback();
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private void awaitBlockedSql(String queryFragment) throws InterruptedException, SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE datname = current_database() AND pid <> pg_backend_pid()
                          AND wait_event_type = 'Lock' AND query LIKE ?
                    )
                    """, Boolean.class, "%" + queryFragment + "%");
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Expected concurrent database statement to wait on a lock");
    }

    @Test
    void rejectsV3HeaderThatClaimsStoreScope() {
        UUID storeId = addStore();
        PersistedWeeklyReviewSnapshot first = store.persist(
                facts(storeId, "0.00", "0.00"), Instant.parse("2026-08-24T04:00:00Z"));

        assertThatThrownBy(() -> insertSyntheticV3(first.id(), UUID.randomUUID(), "STORE", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_weekly_review_snapshot_scope_identity");
    }

    @Test
    void v3ReadRejectsIncorrectContentHash() {
        UUID storeId = addStore();
        PersistedWeeklyReviewSnapshot first = store.persist(
                facts(storeId, "0.00", "0.00"), Instant.parse("2026-08-24T04:00:00Z"));
        UUID v3Id = UUID.randomUUID();
        insertSyntheticV3(first.id(), v3Id, "SELLERS", "f".repeat(64));

        assertThatThrownBy(() -> store.findV3ById(v3Id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity check failed");
    }

    @Test
    void rejectsPayloadWithoutMatchingImmutableHeader() {
        UUID storeId = addStore();
        UUID snapshotId = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO weekly_review_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    report_contract_version, metrics_policy_version,
                    snapshot_policy_version, quality_policy_version,
                    report_state, report_payload, content_hash
                ) VALUES (
                    ?, ?, ?, ?, 'Europe/Kaliningrad', 1, 2,
                    'weekly-metrics-v4', 'weekly-snapshot-v7',
                    'weekly-quality-v4', 'READY', CAST('{}' AS jsonb), ?
                )
                """,
                snapshotId,
                storeId,
                CURRENT.start(),
                CURRENT.end(),
                "a".repeat(64)
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(
                        "ck_weekly_review_snapshot_payload_header"
                );
    }

    @Test
    void exactAiPreflightIsStableAndDoesNotWriteDurableState() {
        UUID storeId = addStore();
        PersistedWeeklyReviewSnapshot snapshot = store.persist(
                facts(storeId, "1000.00", "900.00"),
                Instant.parse("2026-08-24T04:00:00Z")
        );
        DurableCounts before = durableCounts();

        WeeklyReviewAiPreflightView first = aiOperatorService.preflight(
                snapshot.id()
        );
        WeeklyReviewAiPreflightView second = aiOperatorService.preflight(
                snapshot.id()
        );

        assertThat(first.request().inputHash())
                .isEqualTo(second.request().inputHash());
        assertThat(first.request().requestHash())
                .isEqualTo(second.request().requestHash());
        assertThat(first.snapshot().contentHash())
                .isEqualTo(snapshot.contentHash());
        assertThat(first.privacy().verdict())
                .isEqualTo("PASS_STORE_ONLY_SCHEMA");
        assertThat(first.approvalEligible()).isTrue();
        assertThat(durableCounts()).isEqualTo(before);
    }

    private UUID addStore() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = 'livesklad-default'",
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'Weekly review snapshot store',
                    'Europe/Kaliningrad')
                """,
                storeId,
                connectionId,
                "weekly-review-snapshot-" + storeId
        );
        return storeId;
    }

    private SellerWeeklyReviewFacts blockedSellerFacts(UUID storeId) {
        SellerCohortSnapshot cohort = new SellerCohortSnapshot(storeId, List.of(UUID.randomUUID()));
        SellerPeriodMetrics currentMetrics = mock(SellerPeriodMetrics.class);
        SellerPeriodMetrics previousMetrics = mock(SellerPeriodMetrics.class);
        when(currentMetrics.cohort()).thenReturn(cohort);
        when(previousMetrics.cohort()).thenReturn(cohort);
        SellerPeriodFacts current = mock(SellerPeriodFacts.class);
        SellerPeriodFacts previous = mock(SellerPeriodFacts.class);
        when(current.metrics()).thenReturn(currentMetrics);
        when(previous.metrics()).thenReturn(previousMetrics);
        when(current.returnAttribution()).thenReturn(SellerReturnAttributionQuality.COMPLETE);
        when(previous.returnAttribution()).thenReturn(SellerReturnAttributionQuality.COMPLETE);
        SellerPeriodComparisonFacts comparison = mock(SellerPeriodComparisonFacts.class);
        when(comparison.current()).thenReturn(current);
        when(comparison.previous()).thenReturn(previous);
        StoreDataStatusView status = mock(StoreDataStatusView.class);
        when(status.expectedThroughDate()).thenReturn(CURRENT.end());
        SellerWeeklyReviewFacts source = mock(SellerWeeklyReviewFacts.class);
        when(source.storeId()).thenReturn(storeId);
        when(source.period()).thenReturn(PERIOD);
        when(source.comparison()).thenReturn(comparison);
        when(source.sourceDataStatus()).thenReturn(status);
        when(source.sourceStability()).thenReturn(SellerWeeklySourceStability.STABLE);
        SellerWeeklySourceCoverage.Window missing = new SellerWeeklySourceCoverage.Window(false, false);
        when(source.sourceCoverage()).thenReturn(new SellerWeeklySourceCoverage(
                missing, missing, missing));
        return source;
    }

    private WeeklyReviewV3Response insertSyntheticV3(
            UUID v2Id,
            UUID v3Id,
            String reportScope,
            String overriddenHash
    ) {
        PersistedWeeklyReviewSnapshot previous = store.findById(v2Id).orElseThrow();
        String syntheticPayload = v2Codec.serialize(previous.response())
                .replace("STORE.", "SELLERS.")
                .replace("store:", "sellers:")
                .replace("\"STORE\"", "\"SELLERS\"");
        WeeklyReviewResponse legacy = v2Codec.deserialize(syntheticPayload);
        String identityHash = "b".repeat(64);
        String cohortHash = "c".repeat(64);
        BigDecimal zero = BigDecimal.ZERO;
        WeeklyReviewV3Response response = new WeeklyReviewV3Response(
                3, legacy.versions(), legacy.period(),
                new Provenance(v3Id.toString(), 2, Instant.parse("2026-08-24T04:05:00Z"),
                        legacy.provenance().sourceDataUpdatedAt(), true, previous.createdAt()),
                legacy.reportState(), legacy.qualitySummary(), legacy.sourceCoverage().stream()
                        .map(WeeklyReviewV3Response.SellerSourceCoverage::from).toList(),
                "SELLERS", new Membership("CURRENT_RANKING_AT_GENERATION", cohortHash,
                        cohortHash, "e".repeat(64), Instant.parse("2026-08-24T04:05:00Z"), 0),
                identityHash, legacy.summary(), legacy.results(), legacy.revenueDecomposition(),
                new AdditionalSales(legacy.results().get(0), legacy.results().get(1),
                        zero, zero, null, null, zero, false),
                legacy.factors(), legacy.salesStructure(), legacy.team(),
                new TeamDisplay(0, 0, zero, zero, zero, zero),
                List.of(), List.of(), List.of(), List.of(), legacy.aiEnhancement());
        jdbcTemplate.update("""
                INSERT INTO weekly_review_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    supersedes_snapshot_id, report_contract_version, metrics_policy_version,
                    snapshot_policy_version, quality_policy_version, report_state,
                    source_data_updated_at, report_payload, content_hash,
                    report_scope, source_identity_hash
                ) SELECT ?, store_id, period_start, period_end, timezone, 2,
                    id, 3, metrics_policy_version, snapshot_policy_version,
                    quality_policy_version, report_state, source_data_updated_at,
                    CAST(? AS jsonb), ?, ?, ?
                FROM weekly_review_snapshots WHERE id = ?
                """, v3Id, v3Codec.serialize(response),
                overriddenHash == null ? v3Codec.contentHash(response) : overriddenHash,
                reportScope, identityHash, v2Id);
        return response;
    }

    private DurableCounts durableCounts() {
        return new DurableCounts(
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM weekly_review_snapshots",
                        Long.class
                ),
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM weekly_review_ai_jobs",
                        Long.class
                ),
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM weekly_review_ai_enrichments",
                        Long.class
                )
        );
    }

    private record DurableCounts(
            long snapshots,
            long jobs,
            long enrichments
    ) {
    }

    private WeeklyReviewFacts facts(UUID storeId, String currentRevenue, String previousRevenue) {
        return new WeeklyReviewFacts(
                storeId,
                PERIOD,
                status(storeId),
                periodFacts(storeId, currentRevenue, CURRENT),
                periodFacts(storeId, previousRevenue, PREVIOUS),
                Instant.parse("2026-08-24T03:50:00Z")
        );
    }

    private PeriodFacts periodFacts(UUID storeId, String revenue, DateRange period) {
        BigDecimal amount = new BigDecimal(revenue);
        StoreKpiDataQuality quality = new StoreKpiDataQuality(
                true, 0, 0, 0, 0, 0, 0
        );
        StoreKpiResult storeKpi = new StoreKpiResult(
                storeId,
                period.start(),
                period.end(),
                "store-kpi-v3",
                amount,
                BigDecimal.ZERO.setScale(3),
                BigDecimal.ZERO.setScale(2),
                amount,
                amount.signum() <= 0 ? null : new BigDecimal("100.00"),
                quality
        );
        CategoryKpiResult categories = categories(storeId, period);
        AttachRateResult attach = new AttachRateResult(
                storeId,
                period.start(),
                period.end(),
                "attach-rate-v3",
                new AttachRateDataQuality(0, 0, 0),
                List.of()
        );
        EmployeeRatingResult employees = new EmployeeRatingResult(
                storeId,
                period.start(),
                period.end(),
                null,
                null,
                List.of(),
                null
        );
        return new PeriodFacts(
                storeKpi,
                categories,
                attach,
                employees,
                new EmployeeSalesSampleFacts(Map.of()),
                0,
                new RevenuePeriod(amount, BigDecimal.ZERO.setScale(2), amount, 0, 0)
        );
    }

    private CategoryKpiResult categories(UUID storeId, DateRange period) {
        CategoryKpiMetrics zero = new CategoryKpiMetrics(
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(3),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                null,
                null,
                new CategoryKpiDataQuality(true, 0, 0, 0)
        );
        return new CategoryKpiResult(
                storeId,
                period.start(),
                period.end(),
                "category-kpi-v3",
                List.of(
                        new CategoryKpiGroup("PHONES", "Телефоны", zero),
                        new CategoryKpiGroup("DEVICES", "Устройства", zero),
                        new CategoryKpiGroup("ACCESSORY", "Аксессуары", zero),
                        new CategoryKpiGroup("SERVICE", "Услуги", zero),
                        new CategoryKpiGroup(
                                "ADDITIONAL_REVENUE", "Дополнительная выручка", zero
                        )
                ),
                List.of()
        );
    }

    private StoreDataStatusView status(UUID storeId) {
        return new StoreDataStatusView(
                storeId,
                StoreDataFreshnessStatus.CURRENT,
                CURRENT.end(),
                CURRENT.end(),
                CURRENT.end(),
                CURRENT.end(),
                0,
                Instant.parse("2026-08-24T03:50:00Z"),
                null,
                0,
                null,
                null,
                Instant.parse("2026-08-26T12:00:00Z")
        );
    }
}
