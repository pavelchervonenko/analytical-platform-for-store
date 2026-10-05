package com.storeanalytics.interpretation.review.ai;

import static com.storeanalytics.interpretation.review.WeeklyReviewTestPayload.snapshotPayload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.common.exception.PreconditionFailedException;
import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderException;
import com.storeanalytics.interpretation.generation.LlmProviderOutcome;
import com.storeanalytics.interpretation.generation.LlmProviderRequest;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.review.SellerWeeklyReviewProperties;
import com.storeanalytics.interpretation.review.SellerWeeklyV3AssemblerTest;
import com.storeanalytics.interpretation.validation.LlmValidationOutcome;
import com.storeanalytics.interpretation.validation.LlmValidationViolation;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Transactional
@Testcontainers(disabledWithoutDocker = true)
class WeeklyReviewAiJobStoreIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private WeeklyReviewAiJobStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WeeklyReviewAiGenerationProperties properties;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void plansOnlyLatestEligibleRevisionPerStoreIdempotently() {
        UUID firstStore = addStore("AI planner first");
        UUID oldSnapshot = addSnapshot(
                firstStore, LocalDate.of(2026, 8, 10), 1
        );
        UUID latestSnapshot = addSnapshot(
                firstStore, LocalDate.of(2026, 8, 17), 1
        );
        UUID secondStore = addStore("AI planner second");
        UUID secondSnapshot = addSnapshot(
                secondStore, LocalDate.of(2026, 8, 17), 1
        );

        assertThat(store.enqueueLatest(
                "YANDEX", "gpt://folder/yandexgpt-5.1", 2, 10,
                NOW, Duration.ofHours(2)
        )).isEqualTo(2);
        assertThat(store.enqueueLatest(
                "YANDEX", "gpt://folder/yandexgpt-5.1", 2, 10,
                NOW, Duration.ofHours(2)
        )).isZero();
        assertThat(store.findBySnapshot(oldSnapshot)).isEmpty();
        assertThat(store.findBySnapshot(latestSnapshot)).isPresent();
        assertThat(store.findBySnapshot(secondSnapshot)).isPresent();
    }

    @Test
    void doesNotFallBackToOlderReadySnapshotWhenLatestIsBlocked() {
        UUID storeId = addStore("AI planner blocked latest");
        UUID oldReady = addSnapshot(
                storeId, LocalDate.of(2026, 8, 10), 1
        );
        UUID latestBlocked = addSnapshot(
                storeId, LocalDate.of(2026, 8, 17), 1, "BLOCKED"
        );

        assertThat(store.enqueueLatest(
                "YANDEX", "gpt://folder/yandexgpt-5.1", 2, 10,
                NOW, Duration.ofHours(2)
        )).isZero();
        assertThat(store.findBySnapshot(oldReady)).isEmpty();
        assertThat(store.findBySnapshot(latestBlocked)).isEmpty();
    }

    @Test
    void legacyPlannerIgnoresSellerSnapshotsBeforeSelectingLatestCompatibleRevision() {
        UUID storeId = addStore("AI mixed contracts synthetic");
        UUID legacy = addSnapshot(storeId, LocalDate.of(2026, 8, 17), 1);
        UUID seller = UUID.randomUUID();
        String hash = "a".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO weekly_review_snapshots (id, store_id, period_start, period_end, timezone, revision,
                    supersedes_snapshot_id, report_contract_version, metrics_policy_version, snapshot_policy_version,
                    quality_policy_version, report_state, report_payload, content_hash,
                    report_scope, source_identity_hash)
                SELECT ?, store_id, period_start, period_end, timezone, revision + 1, id, 3,
                    metrics_policy_version, snapshot_policy_version, quality_policy_version, report_state,
                    jsonb_set(report_payload, '{provenance}', (report_payload -> 'provenance')
                        || jsonb_build_object('snapshotPublicId', ?::text, 'revision', revision + 1))
                    || jsonb_build_object(
                        'contractVersion', 3, 'scope', 'SELLERS', 'sourceIdentityHash', ?::text,
                        'membership', jsonb_build_object('currentCohortHash', ?::text,
                                                        'previousCohortHash', ?::text)),
                    content_hash, 'SELLERS', ?
                FROM weekly_review_snapshots
                WHERE id = ?
                """, seller, seller.toString(), hash, hash, hash, hash, legacy);
        assertThat(store.enqueueLatest("YANDEX", "synthetic-model", 2, 10,
                NOW, Duration.ofHours(2))).isOne();
        assertThat(store.findBySnapshot(legacy)).isPresent();
        assertThat(store.findBySnapshot(seller)).isEmpty();
        assertThatThrownBy(() -> store.enqueueApproved(seller, "YANDEX", "synthetic-model",
                2, NOW, Duration.ofHours(2))).isInstanceOf(PreconditionFailedException.class);
    }

    @Test
    void automaticSellerAiIsOneJobPerStoreWeekAcrossRevisions() {
        UUID storeId = addStore("AI seller weekly automatic limit");
        UUID firstLegacy = addSnapshot(storeId, LocalDate.of(2026, 8, 17), 1);
        UUID firstSeller = addSellerRevision(firstLegacy);
        UUID correctedSeller = addSellerRevision(firstSeller);
        WeeklyReviewAiJobStore sellerStore = sellerStore();

        assertThatThrownBy(() -> sellerStore.enqueueAutomaticSellerWeek(firstSeller,
                "YANDEX", "synthetic-model", 3, NOW, Duration.ofHours(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most two bounded provider attempts");
        assertThat(automaticSellerEnqueue(sellerStore, firstSeller)).isTrue();
        UUID firstJob = sellerStore.findBySnapshot(firstSeller).orElseThrow().id();
        jdbcTemplate.update("UPDATE weekly_review_ai_jobs SET status = 'FAILED' WHERE id = ?", firstJob);
        assertThat(automaticSellerEnqueue(sellerStore, correctedSeller)).isFalse();
        assertThat(sellerStore.findBySnapshot(correctedSeller)).isEmpty();

        UUID nextLegacy = addSnapshot(storeId, LocalDate.of(2026, 8, 24), 1);
        UUID nextSeller = addSellerRevision(nextLegacy);
        assertThat(automaticSellerEnqueue(sellerStore, nextSeller)).isTrue();

        new TransactionTemplate(transactionManager).execute(status ->
                sellerStore.enqueueApproved(correctedSeller, "YANDEX", "synthetic-model",
                        1, NOW, Duration.ofHours(2)));
        assertThat(sellerStore.findBySnapshot(correctedSeller)).isPresent();
    }

    @Test
    void unpaidAutomaticRevisionKeepsTheSameJobDeadlineAndPaidCallCap() {
        Instant now = Instant.now();
        UUID first = addSellerRevision(addSnapshot(addStore("Unpaid automatic revision"),
                LocalDate.of(2026, 8, 17), 1));
        WeeklyReviewAiJobStore sellers = sellerStore();
        assertThat(automaticSellerEnqueue(sellers, first, now)).isTrue();
        WeeklyReviewAiJob original = sellers.findBySnapshot(first).orElseThrow();
        UUID corrected = addSellerRevision(first);
        assertThat(new TransactionTemplate(transactionManager).<Boolean>execute(status ->
                sellers.enqueueAutomaticSellerWeek(corrected, "YANDEX", "synthetic-model", 2,
                        now.plusSeconds(1), Duration.ofDays(1)))).isTrue();
        WeeklyReviewAiJob rebound = sellers.findBySnapshot(corrected).orElseThrow();
        assertThat(rebound.id()).isEqualTo(original.id());
        assertThat(rebound.deadlineAt()).isEqualTo(original.deadlineAt());
        assertThat(rebound.maxAttempts()).isEqualTo(original.maxAttempts()).isOne();
        assertThat(rebound.attemptCount()).isZero();
        assertThat(sellers.findBySnapshot(first)).isEmpty();
        assertThat(automaticSellerEnqueue(sellers, corrected, now.plusSeconds(2))).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT planning_origin FROM weekly_review_ai_jobs WHERE id=?",
                String.class, rebound.id())).isEqualTo("AUTOMATIC");
    }

    @Test
    void activeLeaseCannotRebindAndExpiredUnpaidClaimCannotStartAfterRebind() {
        Instant now = Instant.now();
        UUID first = addSellerRevision(addSnapshot(addStore("Unpaid automatic stale claim"),
                LocalDate.of(2026, 8, 17), 1));
        WeeklyReviewAiJobStore sellers = sellerStore();
        assertThat(automaticSellerEnqueue(sellers, first, now)).isTrue();
        WeeklyReviewAiJob claim = sellers.claimNext("old", Duration.ofMinutes(4), now).orElseThrow();
        UUID corrected = addSellerRevision(first);
        assertThat(automaticSellerEnqueue(sellers, corrected, now.plusSeconds(1))).isFalse();
        jdbcTemplate.update("UPDATE weekly_review_ai_jobs SET lease_until=? WHERE id=?",
                Timestamp.from(now.minusSeconds(1)), claim.id());
        assertThat(automaticSellerEnqueue(sellers, corrected, now.plusSeconds(2))).isTrue();
        SellerWeeklyReviewAiInput input = new SellerWeeklyReviewAiInputCompactor()
                .compact(SellerWeeklyV3AssemblerTest.syntheticResponse());
        assertThatThrownBy(() -> sellers.startAttempt(claim, "old", prepared(claim, input, now),
                preflight(), now.plusSeconds(2))).isInstanceOf(WeeklyReviewAiLeaseLostException.class);
        assertThat(sellers.findBySnapshot(corrected).orElseThrow().attemptCount()).isZero();
    }

    @Test
    void exactApprovalNeverAdoptsAnotherSnapshotEvenWithoutSpend() {
        Instant now = Instant.now();
        UUID first = addSellerRevision(addSnapshot(addStore("Exact approval stays exact"),
                LocalDate.of(2026, 8, 17), 1));
        WeeklyReviewAiJobStore sellers = sellerStore();
        WeeklyReviewAiJob exact = sellers.enqueueApproved(first, "YANDEX", "synthetic-model", 1,
                now, Duration.ofHours(2));
        UUID corrected = addSellerRevision(first);
        assertThat(automaticSellerEnqueue(sellers, corrected, now.plusSeconds(1))).isFalse();
        assertThat(sellers.findById(exact.id()).orElseThrow().snapshotId()).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT planning_origin FROM weekly_review_ai_jobs WHERE id=?",
                String.class, exact.id())).isEqualTo("EXACT");
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE weekly_review_ai_jobs SET snapshot_id=? WHERE id=?",
                corrected, exact.id())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void evenOneStartedAttemptPreventsRebindingAndCreatingAnotherAutomaticJob() {
        Instant now = Instant.now();
        UUID first = addSellerRevision(addSnapshot(addStore("Paid automatic immutable binding"),
                LocalDate.of(2026, 8, 17), 1));
        WeeklyReviewAiJobStore sellers = sellerStore();
        assertThat(automaticSellerEnqueue(sellers, first, now)).isTrue();
        WeeklyReviewAiJob claim = sellers.claimNext("paid", Duration.ofMinutes(4), now).orElseThrow();
        SellerWeeklyReviewAiInput input = new SellerWeeklyReviewAiInputCompactor()
                .compact(SellerWeeklyV3AssemblerTest.syntheticResponse());
        WeeklyReviewAiAttempt attempt = sellers.startAttempt(claim, "paid", prepared(claim, input, now),
                preflight(), now);
        UUID corrected = addSellerRevision(first);
        assertThat(automaticSellerEnqueue(sellers, corrected, now.plusSeconds(241))).isFalse();
        assertThat(sellers.findById(claim.id()).orElseThrow().snapshotId()).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM weekly_review_ai_attempts WHERE job_id=?",
                Integer.class, claim.id())).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT request_hash FROM weekly_review_ai_attempts WHERE id=?",
                String.class, attempt.id())).isEqualTo("b".repeat(64));
    }

    @Test
    void freeSourceFailureCanRebindButDeadlineTechnicalFailureAndModelChangeCannot() {
        Instant now = Instant.now();
        UUID first = addSellerRevision(addSnapshot(addStore("Free source failure refresh"),
                LocalDate.of(2026, 8, 17), 1));
        WeeklyReviewAiJobStore sellers = sellerStore();
        assertThat(automaticSellerEnqueue(sellers, first, now)).isTrue();
        WeeklyReviewAiJob original = sellers.findBySnapshot(first).orElseThrow();
        jdbcTemplate.update("UPDATE weekly_review_ai_jobs SET status='FAILED', "
                + "last_error_code='SNAPSHOT_NOT_CURRENT' WHERE id=?", original.id());
        UUID corrected = addSellerRevision(first);
        assertThat(new TransactionTemplate(transactionManager).<Boolean>execute(status ->
                sellers.enqueueAutomaticSellerWeek(corrected, "YANDEX", "different-model", 1,
                        now.plusSeconds(1), Duration.ofHours(2)))).isFalse();
        assertThat(automaticSellerEnqueue(sellers, corrected, now.plusSeconds(1))).isTrue();
        UUID later = addSellerRevision(corrected);
        jdbcTemplate.update("UPDATE weekly_review_ai_jobs SET status='FAILED', "
                + "last_error_code='VALIDATION_EXECUTION_FAILED' WHERE id=?", original.id());
        assertThat(automaticSellerEnqueue(sellers, later, now.plusSeconds(2))).isFalse();
        jdbcTemplate.update("UPDATE weekly_review_ai_jobs SET status='PENDING' WHERE id=?", original.id());
        assertThat(automaticSellerEnqueue(sellers, later, original.deadlineAt())).isFalse();
        assertThat(sellers.findById(original.id()).orElseThrow().snapshotId()).isEqualTo(corrected);
    }

    @Test
    void databaseRejectsProvenanceReclassificationOfAnExistingExactJob() {
        UUID first = addSellerRevision(addSnapshot(addStore("Immutable planning origin"),
                LocalDate.of(2026, 8, 17), 1));
        WeeklyReviewAiJob exact = sellerStore().enqueue(first, "YANDEX", "synthetic-model", 1,
                NOW, Duration.ofHours(2));
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs job SET planning_origin='AUTOMATIC',
                    automatic_store_id=report.store_id, automatic_period_start=report.period_start,
                    automatic_period_end=report.period_end FROM weekly_review_snapshots report
                WHERE job.id=? AND report.id=job.snapshot_id
                """, exact.id())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseUniqueKeyPreventsAnotherAutomaticJobForTheSameWeek() {
        UUID first = addSellerRevision(addSnapshot(addStore("Unique automatic week"),
                LocalDate.of(2026, 8, 17), 1));
        WeeklyReviewAiJobStore sellers = sellerStore();
        assertThat(automaticSellerEnqueue(sellers, first)).isTrue();
        UUID corrected = addSellerRevision(first);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO weekly_review_ai_jobs(snapshot_id,prompt_version,content_schema_version,
                    provider_code,requested_model,status,max_attempts,next_attempt_at,deadline_at,
                    planning_origin,automatic_store_id,automatic_period_start,automatic_period_end)
                SELECT ?,prompt_version,content_schema_version,provider_code,requested_model,'PENDING',
                    max_attempts,now(),now()+interval '2h','AUTOMATIC',automatic_store_id,
                    automatic_period_start,automatic_period_end FROM weekly_review_ai_jobs WHERE snapshot_id=?
                """, corrected, first)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void startsExactlyOneProviderAttemptForActiveSellerV26Job() {
        UUID storeId = addStore("AI seller v26 attempt");
        UUID legacySnapshot = addSnapshot(storeId, LocalDate.of(2026, 8, 17), 1);
        UUID sellerSnapshot = addSellerRevision(legacySnapshot);
        WeeklyReviewAiJobStore sellerStore = sellerStore();
        WeeklyReviewAiJob pending = sellerStore.enqueueApproved(
                sellerSnapshot, "YANDEX", "synthetic-model", 1,
                NOW, Duration.ofHours(2));
        jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs
                SET status = 'RUNNING', lease_owner = 'seller-worker', lease_until = ?
                WHERE id = ?
                """, Timestamp.from(NOW.plus(Duration.ofMinutes(4))), pending.id());
        WeeklyReviewAiJob claimed = sellerStore.findById(pending.id()).orElseThrow();
        assertThat(claimed.id()).isEqualTo(pending.id());

        SellerWeeklyReviewAiInput input = new SellerWeeklyReviewAiInputCompactor()
                .compact(SellerWeeklyV3AssemblerTest.syntheticResponse());
        WeeklyReviewAiAttempt attempt = sellerStore.startAttempt(
                claimed, "seller-worker", prepared(claimed, input), preflight(), NOW);

        assertThat(attempt.attemptNumber()).isOne();
        assertThat(sellerStore.findById(pending.id()).orElseThrow().attemptCount()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM weekly_review_ai_attempts WHERE job_id = ?",
                Integer.class, pending.id())).isOne();
    }

    @Test
    void expiredSellerLeaseCannotStartPaidAttemptOrBeResurrectedByHeartbeat() {
        UUID legacy = addSnapshot(addStore("AI expired seller lease"), LocalDate.of(2026, 8, 17), 1);
        WeeklyReviewAiJobStore sellerStore = sellerStore();
        WeeklyReviewAiJob pending = sellerStore.enqueueApproved(addSellerRevision(legacy),
                "YANDEX", "synthetic-model", 1, NOW, Duration.ofHours(2));
        jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs SET status = 'RUNNING', lease_owner = 'expired', lease_until = ?
                WHERE id = ?
                """, Timestamp.from(NOW), pending.id());
        WeeklyReviewAiJob claimed = sellerStore.findById(pending.id()).orElseThrow();
        SellerWeeklyReviewAiInput input = new SellerWeeklyReviewAiInputCompactor()
                .compact(SellerWeeklyV3AssemblerTest.syntheticResponse());

        assertThat(sellerStore.heartbeat(pending.id(), "expired", Duration.ofMinutes(4), NOW)).isFalse();
        assertThatThrownBy(() -> sellerStore.startAttempt(claimed, "expired",
                prepared(claimed, input), preflight(), NOW)).isInstanceOf(WeeklyReviewAiLeaseLostException.class);
        assertThat(sellerStore.findById(pending.id()).orElseThrow().attemptCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM weekly_review_ai_attempts WHERE job_id = ?",
                Integer.class, pending.id())).isZero();
    }

    @Test
    void heartbeatNeverShortensLeaseAndNeverExtendsPastDeadline() {
        UUID snapshot = addSnapshot(addStore("AI heartbeat boundaries"), LocalDate.of(2026, 8, 17), 1);
        WeeklyReviewAiJob pending = store.enqueue(snapshot, "YANDEX", "synthetic-model", 1,
                NOW, Duration.ofHours(2));
        jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs SET status = 'RUNNING', lease_owner = 'worker', lease_until = ?
                WHERE id = ?
                """, Timestamp.from(NOW.plusSeconds(240)), pending.id());
        assertThat(store.heartbeat(pending.id(), "worker", Duration.ofSeconds(60), NOW)).isTrue();
        assertThat(store.findById(pending.id()).orElseThrow().leaseUntil()).isEqualTo(NOW.plusSeconds(240));
        assertThat(store.heartbeat(pending.id(), "other-owner", Duration.ofHours(3), NOW)).isFalse();
        assertThat(store.heartbeat(pending.id(), "worker", Duration.ofHours(3), NOW)).isTrue();
        assertThat(store.findById(pending.id()).orElseThrow().leaseUntil()).isEqualTo(pending.deadlineAt());
        assertThat(store.heartbeat(pending.id(), "worker", Duration.ofMinutes(4), pending.deadlineAt())).isFalse();
    }

    @Test
    void repeatedStartWithTheSameClaimCannotConsumeASecondPaidAttempt() {
        UUID snapshot = addSnapshot(addStore("AI duplicate attempt fence"), LocalDate.of(2026, 8, 17), 1);
        WeeklyReviewAiJob pending = store.enqueue(snapshot, "YANDEX", "synthetic-model", 2,
                NOW, Duration.ofHours(2));
        jdbcTemplate.update("""
                UPDATE weekly_review_ai_jobs SET status = 'RUNNING', lease_owner = 'worker', lease_until = ?
                WHERE id = ?
                """, Timestamp.from(NOW.plusSeconds(240)), pending.id());
        WeeklyReviewAiJob claimed = store.findById(pending.id()).orElseThrow();
        PreparedWeeklyReviewAiRequest request = prepared(claimed, input());
        assertThat(store.startAttempt(claimed, "worker", request, preflight(), NOW).attemptNumber()).isOne();
        assertThatThrownBy(() -> store.startAttempt(claimed, "worker", request, preflight(), NOW))
                .isInstanceOf(WeeklyReviewAiLeaseLostException.class);
        assertThat(store.findById(pending.id()).orElseThrow().attemptCount()).isOne();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM weekly_review_ai_attempts WHERE job_id = ?",
                Integer.class, pending.id())).isOne();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAutomaticSellerAiAcrossRevisionsCreatesOneJob() throws Exception {
        UUID storeId = addStore("AI seller weekly concurrent limit");
        UUID legacy = addSnapshot(storeId, LocalDate.of(2026, 8, 17), 1);
        // The concurrent test is committed; keep its legacy base out of later v2 planner cases.
        UUID legacyJob = store.enqueue(legacy, "YANDEX", "synthetic-model",
                1, NOW, Duration.ofHours(2)).id();
        jdbcTemplate.update("UPDATE weekly_review_ai_jobs SET status = 'FAILED' WHERE id = ?", legacyJob);
        UUID firstSeller = addSellerRevision(legacy);
        UUID correctedSeller = addSellerRevision(firstSeller);
        WeeklyReviewAiJobStore sellerStore = sellerStore();
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await(20, TimeUnit.SECONDS);
                return automaticSellerEnqueue(sellerStore, firstSeller);
            });
            var second = executor.submit(() -> {
                start.await(20, TimeUnit.SECONDS);
                return automaticSellerEnqueue(sellerStore, correctedSeller);
            });
            assertThat(java.util.List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM weekly_review_ai_jobs job
                    JOIN weekly_review_snapshots report ON report.id = job.snapshot_id
                    WHERE report.store_id = ? AND report.period_start = ?
                      AND report.report_contract_version = 3
                    """, Integer.class, storeId, LocalDate.of(2026, 8, 17));
            assertThat(count).isOne();
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update("""
                    UPDATE weekly_review_ai_jobs SET status = 'FAILED'
                    WHERE snapshot_id IN (?, ?) AND status IN ('PENDING', 'RETRY_WAIT')
                    """, firstSeller, correctedSeller);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentApprovedEnqueueCreatesExactlyOneJob() throws Exception {
        UUID snapshotId = addSnapshot(
                addStore("AI approved concurrency"),
                LocalDate.of(2026, 8, 17),
                1
        );
        CyclicBarrier start = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> approvedEnqueue(
                    snapshotId, start
            ));
            var second = executor.submit(() -> approvedEnqueue(
                    snapshotId, start
            ));

            assertThat(java.util.List.of(
                    first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder("CREATED", "REJECTED");
            assertThat(store.findBySnapshot(snapshotId))
                    .get()
                    .extracting(WeeklyReviewAiJob::maxAttempts)
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            jdbcTemplate.update("""
                    UPDATE weekly_review_ai_jobs SET status = 'FAILED'
                    WHERE snapshot_id = ? AND status IN ('PENDING', 'RETRY_WAIT')
                    """, snapshotId);
        }
    }

    @Test
    void activeWorkerLeavesPendingLegacyJobUntouched() {
        Instant legacyNow = NOW.minus(Duration.ofDays(365));
        UUID snapshotId = addSnapshot(
                addStore("AI legacy pending"), LocalDate.of(2025, 8, 17), 1,
                "BLOCKED"
        );
        long activePendingBefore = store.countByStatus(
                WeeklyReviewAiJobStatus.PENDING
        );
        UUID legacyJobId = addLegacyJob(snapshotId, legacyNow);

        assertThat(store.countByStatus(WeeklyReviewAiJobStatus.PENDING))
                .isEqualTo(activePendingBefore);

        assertThat(store.claimNext(
                "v23-worker", Duration.ofMinutes(4), legacyNow
        )).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_jobs WHERE id = ?",
                String.class,
                legacyJobId
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM weekly_review_ai_jobs WHERE id = ?",
                Integer.class,
                legacyJobId
        )).isZero();
    }

    @Test
    void retiresSupersededJobOnlyAfterItsDeadline() {
        Instant createdAt = NOW.minus(Duration.ofDays(2));
        UUID snapshotId = addSnapshot(
                addStore("AI superseded deadline"),
                LocalDate.of(2026, 8, 10),
                1
        );
        UUID legacyJobId = addLegacyJob(snapshotId, createdAt);

        store.claimNext("v25-worker", Duration.ofMinutes(4), NOW);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_jobs WHERE id = ?",
                String.class,
                legacyJobId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_error_code FROM weekly_review_ai_jobs "
                        + "WHERE id = ?",
                String.class,
                legacyJobId
        )).isEqualTo("JOB_CONTRACT_SUPERSEDED");
    }

    @Test
    void retriesSemanticFailureThenFinalizesImmutableSuccess() {
        UUID snapshotId = addSnapshot(
                addStore("AI retry"), LocalDate.of(2026, 8, 17), 1
        );
        WeeklyReviewAiJob pending = store.enqueue(
                snapshotId, "YANDEX", "gpt://folder/yandexgpt-5.1", 2,
                NOW, Duration.ofHours(2)
        );
        WeeklyReviewAiJob firstClaim = store.claimNext(
                "worker-1", Duration.ofMinutes(4), NOW
        ).orElseThrow();
        assertThat(firstClaim.id()).isEqualTo(pending.id());
        WeeklyReviewAiInput input = input();
        WeeklyReviewAiAttempt firstAttempt = store.startAttempt(
                firstClaim,
                "worker-1",
                prepared(firstClaim, input),
                preflight(),
                NOW
        );
        WeeklyReviewAiValidationResult invalid = validator().validate(
                input,
                response("Чистая выручка выросла на 12%.")
        );
        assertThat(invalid.semanticValidated()).isFalse();
        store.recordValidationFailure(
                firstClaim,
                firstAttempt,
                "worker-1",
                receipt(response("Чистая выручка выросла на 12%.")),
                invalid,
                Duration.ofSeconds(30),
                NOW.plusSeconds(1)
        );

        WeeklyReviewAiJob retry = store.findById(pending.id()).orElseThrow();
        assertThat(retry.status()).isEqualTo(WeeklyReviewAiJobStatus.RETRY_WAIT);
        assertThat(retry.attemptCount()).isOne();
        assertThat(retry.lastValidationCodes()).contains("SUMMARY_SELECTOR_NOT_ALLOWED");
        WeeklyReviewAiJob secondClaim = store.claimNext(
                "worker-2", Duration.ofMinutes(4), NOW.plusSeconds(31)
        ).orElseThrow();
        WeeklyReviewAiAttempt secondAttempt = store.startAttempt(
                secondClaim,
                "worker-2",
                prepared(secondClaim, input),
                preflight(),
                NOW.plusSeconds(31)
        );
        WeeklyReviewAiValidationResult valid = validator().validate(
                input,
                response("Чистая выручка выросла.")
        );
        store.recordSuccessfulAttempt(
                secondClaim,
                secondAttempt,
                "worker-2",
                receipt(response("Чистая выручка выросла.")),
                valid,
                NOW.plusSeconds(32)
        );

        WeeklyReviewAiJob succeeded = store.findById(pending.id()).orElseThrow();
        assertThat(succeeded.status()).isEqualTo(WeeklyReviewAiJobStatus.SUCCEEDED);
        assertThat(succeeded.attemptCount()).isEqualTo(2);
        assertThat(store.actualCostSince(NOW.minusSeconds(1)))
                .isEqualByComparingTo("4.00");
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE weekly_review_ai_attempts
                SET error_code = 'MUTATED'
                WHERE id = ?
                """,
                firstAttempt.id()
        )).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("Final weekly review AI attempts are immutable");
    }

    @Test
    void supersededRunningJobWaitsForLeaseThenClosesAttempt() {
        Instant createdAt = NOW.minus(Duration.ofDays(2));
        UUID snapshotId = addSnapshot(
                addStore("AI superseded running"),
                LocalDate.of(2026, 8, 10),
                1
        );
        UUID legacyJobId = addLegacyJob(snapshotId, createdAt);
        UUID attemptId = UUID.randomUUID();
        Instant liveLease = NOW.plus(Duration.ofMinutes(2));
        jdbcTemplate.update(
                "UPDATE weekly_review_ai_jobs SET status = 'RUNNING', "
                        + "attempt_count = 1, lease_owner = 'legacy', "
                        + "lease_until = ? WHERE id = ?",
                java.sql.Timestamp.from(liveLease),
                legacyJobId
        );
        jdbcTemplate.update(
                """
                INSERT INTO weekly_review_ai_attempts (
                    id, job_id, attempt_number, status, request_hash,
                    input_hash, input_payload, estimated_cost, started_at
                ) VALUES (?, ?, 1, 'STARTED', ?, ?, '{}'::jsonb, ?, ?)
                """,
                attemptId,
                legacyJobId,
                "a".repeat(64),
                "b".repeat(64),
                new BigDecimal("3.00"),
                java.sql.Timestamp.from(createdAt)
        );

        store.claimNext("v25-before-expiry", Duration.ofMinutes(4), NOW);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_jobs WHERE id = ?",
                String.class, legacyJobId
        )).isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_attempts WHERE id = ?",
                String.class, attemptId
        )).isEqualTo("STARTED");

        store.claimNext(
                "v25-after-expiry", Duration.ofMinutes(4),
                liveLease.plusSeconds(1)
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_jobs WHERE id = ?",
                String.class, legacyJobId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_attempts WHERE id = ?",
                String.class, attemptId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_outcome FROM weekly_review_ai_attempts "
                        + "WHERE id = ?",
                String.class, attemptId
        )).isEqualTo("UNKNOWN");
    }

    @Test
    void expiredLeaseAfterProviderStartRecordsUnknownOutcomeWithoutBlindRetry() {
        UUID snapshotId = addSnapshot(
                addStore("AI lease"), LocalDate.of(2026, 8, 17), 1
        );
        WeeklyReviewAiJob pending = store.enqueue(
                snapshotId, "YANDEX", "gpt://folder/yandexgpt-5.1", 2,
                NOW, Duration.ofHours(2)
        );
        WeeklyReviewAiJob claim = store.claimNext(
                "worker-old", Duration.ofMinutes(4), NOW
        ).orElseThrow();
        WeeklyReviewAiAttempt attempt = store.startAttempt(
                claim, "worker-old", prepared(claim, input()), preflight(), NOW
        );
        jdbcTemplate.update(
                "UPDATE weekly_review_ai_jobs SET lease_until = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minusSeconds(1)),
                pending.id()
        );

        assertThat(store.claimNext(
                "worker-new", Duration.ofMinutes(4), NOW.plusSeconds(1)
        )).isEmpty();

        WeeklyReviewAiJob failed = store.findById(pending.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(WeeklyReviewAiJobStatus.FAILED);
        assertThat(failed.attemptCount()).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM weekly_review_ai_attempts WHERE id = ?",
                String.class,
                attempt.id()
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_outcome FROM weekly_review_ai_attempts WHERE id = ?",
                String.class, attempt.id())).isEqualTo("UNKNOWN");
    }

    @Test
    void expiredLeaseBeforeProviderStartCanBeReclaimedWithoutSpend() {
        UUID snapshot = addSnapshot(addStore("AI free lease recovery"), LocalDate.of(2026, 8, 17), 1);
        WeeklyReviewAiJob pending = store.enqueue(snapshot, "YANDEX", "synthetic-model", 2,
                NOW, Duration.ofHours(2));
        store.claimNext("old", Duration.ofMinutes(4), NOW).orElseThrow();
        WeeklyReviewAiJob recovered = store.claimNext("new", Duration.ofMinutes(4), NOW.plusSeconds(241))
                .orElseThrow();
        assertThat(recovered.id()).isEqualTo(pending.id());
        assertThat(recovered.attemptCount()).isZero();
        assertThat(recovered.leaseOwner()).isEqualTo("new");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM weekly_review_ai_attempts WHERE job_id = ?",
                Integer.class, pending.id())).isZero();
    }

    @Test
    void automaticSellerSemanticRetryIsBoundedInsideOneWeeklyJob() {
        UUID legacy = addSnapshot(addStore("AI automatic bounded retry"), LocalDate.of(2026, 8, 17), 1);
        UUID snapshot = addSellerRevision(legacy);
        WeeklyReviewAiJobStore sellerStore = sellerStore();
        Boolean planned = new TransactionTemplate(transactionManager).execute(status ->
                sellerStore.enqueueAutomaticSellerWeek(
                        snapshot, "YANDEX", "synthetic-model", 2, NOW, Duration.ofHours(2)));
        assertThat(planned).isTrue();
        SellerWeeklyReviewAiInput input = new SellerWeeklyReviewAiInputCompactor()
                .compact(SellerWeeklyV3AssemblerTest.syntheticResponse());
        WeeklyReviewAiJob first = sellerStore.claimNext("first", Duration.ofMinutes(4), NOW).orElseThrow();
        WeeklyReviewAiAttempt attempt = sellerStore.startAttempt(
                first, "first", prepared(first, input), preflight(), NOW);
        WeeklyReviewAiValidationResult invalid = WeeklyReviewAiValidationResult.invalid(
                LlmValidationOutcome.SEMANTIC_INVALID,
                List.of(new LlmValidationViolation(
                        "SYNTHETIC_INVALID", "$", null)));
        sellerStore.recordValidationFailure(first, attempt, "first", receipt("{}"), invalid,
                Duration.ofSeconds(30), NOW.plusSeconds(1));
        WeeklyReviewAiJob second = sellerStore.claimNext("second", Duration.ofMinutes(4), NOW.plusSeconds(32))
                .orElseThrow();
        assertThat(second.id()).isEqualTo(first.id());
        WeeklyReviewAiAttempt secondAttempt = sellerStore.startAttempt(second, "second", prepared(second, input),
                preflight(), NOW.plusSeconds(32));
        sellerStore.recordValidationFailure(second, secondAttempt, "second", receipt("{}"), invalid,
                Duration.ofSeconds(30), NOW.plusSeconds(33));
        assertThat(sellerStore.findById(first.id()).orElseThrow().status()).isEqualTo(WeeklyReviewAiJobStatus.FAILED);
        assertThat(sellerStore.findById(first.id()).orElseThrow().attemptCount()).isEqualTo(2);
        assertThat(sellerStore.claimNext("third", Duration.ofMinutes(4), NOW.plusSeconds(64))).isEmpty();
        assertThat(automaticSellerEnqueue(sellerStore, addSellerRevision(snapshot))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(LlmProviderOutcome.class)
    void unknownProviderFailureDoesNotRetryButKnownRetryableFailureDoes(LlmProviderOutcome outcome) {
        UUID snapshot = addSnapshot(addStore("AI failure outcomes " + outcome), LocalDate.of(2026, 8, 17), 1);
        store.enqueue(snapshot, "YANDEX", "synthetic-model", 2, NOW, Duration.ofHours(2));
        WeeklyReviewAiJob claim = store.claimNext("worker", Duration.ofMinutes(4), NOW).orElseThrow();
        WeeklyReviewAiAttempt attempt = store.startAttempt(claim, "worker", prepared(claim, input()), preflight(), NOW);
        store.recordProviderFailure(claim, attempt, "worker", retryableFailure(outcome), Duration.ofSeconds(30), NOW);
        assertThat(store.findById(claim.id()).orElseThrow().status()).isEqualTo(
                outcome == LlmProviderOutcome.UNKNOWN
                        ? WeeklyReviewAiJobStatus.FAILED : WeeklyReviewAiJobStatus.RETRY_WAIT);
    }

    private LlmProviderException retryableFailure(LlmProviderOutcome outcome) {
        return new LlmProviderException("Synthetic failure", null) {
            @Override
            public String failureCode() {
                return "SYNTHETIC";
            }

            @Override
            public LlmProviderOutcome outcome() {
                return outcome;
            }

            @Override
            public Integer httpStatus() {
                return null;
            }

            @Override
            public Duration retryAfter() {
                return null;
            }

            @Override
            public boolean isRetryable() {
                return true;
            }
        };
    }

    private UUID addLegacyJob(UUID snapshotId, Instant createdAt) {
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO weekly_review_ai_jobs (
                    id, snapshot_id, prompt_version, content_schema_version,
                    provider_code, requested_model, status, attempt_count,
                    max_attempts, next_attempt_at, deadline_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, 4, ?, ?, ?, 0, 2, ?, ?, ?, ?)
                """,
                jobId,
                snapshotId,
                WeeklyReviewAiContract.LEGACY_PROMPT_VERSION,
                "YANDEX",
                "gpt://folder/yandexgpt-5.1",
                "PENDING",
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt.plus(Duration.ofHours(2))),
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt)
        );
        return jobId;
    }

    private String approvedEnqueue(
            UUID snapshotId,
            CyclicBarrier start
    ) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        try {
            store.enqueueApproved(
                    snapshotId,
                    "YANDEX",
                    "gpt://folder/yandexgpt-5.1",
                    1,
                    NOW,
                    Duration.ofHours(2)
            );
            return "CREATED";
        } catch (PreconditionFailedException expected) {
            return "REJECTED";
        }
    }

    private PreparedWeeklyReviewAiRequest prepared(
            WeeklyReviewAiJob job,
            WeeklyReviewAiEditorialInput input
    ) {
        return prepared(job, input, NOW);
    }

    private PreparedWeeklyReviewAiRequest prepared(WeeklyReviewAiJob job,
            WeeklyReviewAiEditorialInput input, Instant preparedAt) {
        String inputJson = new WeeklyReviewAiContentCodec().canonical(input);
        LlmProviderRequest request = new LlmProviderRequest(
                job.id(), job.providerCode(), job.requestedModel(), "system",
                inputJson, "{}", new BigDecimal("0.1"), 1400,
                preparedAt.plus(Duration.ofMinutes(3))
        );
        return new PreparedWeeklyReviewAiRequest(
                request, "b".repeat(64), input, "c".repeat(64)
        );
    }

    private LlmProviderPreflight preflight() {
        return new LlmProviderPreflight(
                1000, 8000, new BigDecimal("3.00"), "RUB"
        );
    }

    private LlmProviderResponseReceipt receipt(String body) {
        return new LlmProviderResponseReceipt(
                body,
                "gpt://folder/yandexgpt-5.1",
                UUID.randomUUID().toString(),
                1000, 100, 0, 0, 1100,
                new BigDecimal("2.00"), "RUB", 500L, 200
        );
    }

    private WeeklyReviewAiSemanticValidator validator() {
        return new WeeklyReviewAiSemanticValidator(
                new WeeklyReviewAiStructuralValidator()
        );
    }

    private WeeklyReviewAiInput input() {
        return WeeklyReviewAiTestFixtures.minimalInput("POSITIVE");
    }

    private String response(String text) {
        if (text.contains("12%")) {
            return WeeklyReviewAiTestFixtures.outcomeSelection().replace(
                    "SUMMARY_OUTCOME", "SUMMARY_RISK"
            );
        }
        return WeeklyReviewAiTestFixtures.outcomeSelection();
    }

    private WeeklyReviewAiJobStore sellerStore() {
        return new WeeklyReviewAiJobStore(jdbcTemplate, objectMapper, properties,
                new SellerWeeklyReviewProperties(true));
    }

    private boolean automaticSellerEnqueue(WeeklyReviewAiJobStore sellerStore, UUID snapshotId) {
        return automaticSellerEnqueue(sellerStore, snapshotId, NOW);
    }

    private boolean automaticSellerEnqueue(WeeklyReviewAiJobStore sellerStore, UUID snapshotId, Instant now) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status ->
                sellerStore.enqueueAutomaticSellerWeek(snapshotId, "YANDEX", "synthetic-model",
                        1, now, Duration.ofHours(2))));
    }

    private UUID addSellerRevision(UUID predecessor) {
        UUID snapshotId = UUID.randomUUID();
        String hash = "a".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO weekly_review_snapshots (id, store_id, period_start, period_end, timezone, revision,
                    supersedes_snapshot_id, report_contract_version, metrics_policy_version, snapshot_policy_version,
                    quality_policy_version, report_state, report_payload, content_hash,
                    report_scope, source_identity_hash)
                SELECT ?, store_id, period_start, period_end, timezone, revision + 1, id, 3,
                    metrics_policy_version, snapshot_policy_version, quality_policy_version, report_state,
                    jsonb_set(report_payload, '{provenance}', (report_payload -> 'provenance')
                        || jsonb_build_object('snapshotPublicId', ?::text, 'revision', revision + 1))
                    || jsonb_build_object(
                        'contractVersion', 3, 'scope', 'SELLERS', 'sourceIdentityHash', ?::text,
                        'membership', jsonb_build_object('currentCohortHash', ?::text,
                                                        'previousCohortHash', ?::text)),
                    content_hash, 'SELLERS', ?
                FROM weekly_review_snapshots
                WHERE id = ?
                """, snapshotId, snapshotId.toString(), hash, hash, hash, hash, predecessor);
        return snapshotId;
    }

    private UUID addStore(String name) {
        UUID connectionId = jdbcTemplate.queryForObject(
                """
                SELECT id FROM integration_connections
                WHERE connection_key = 'livesklad-default'
                """,
                UUID.class
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, ?, 'Europe/Moscow')
                """,
                storeId,
                connectionId,
                "weekly-review-ai-job-" + storeId,
                name
        );
        return storeId;
    }

    private UUID addSnapshot(
            UUID storeId,
            LocalDate periodStart,
            int revision
    ) {
        return addSnapshot(storeId, periodStart, revision, "READY");
    }

    private UUID addSnapshot(
            UUID storeId,
            LocalDate periodStart,
            int revision,
            String reportState
    ) {
        UUID snapshotId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO weekly_review_snapshots (
                    id, store_id, period_start, period_end, timezone, revision,
                    report_contract_version, metrics_policy_version,
                    snapshot_policy_version, quality_policy_version,
                    report_state, report_payload, content_hash
                ) VALUES (
                    ?, ?, ?, ?, 'Europe/Moscow', ?, 2,
                    'metrics-v4', 'snapshot-v7', 'quality-v4',
                    ?, CAST(? AS jsonb), ?
                )
                """,
                snapshotId,
                storeId,
                periodStart,
                periodStart.plusDays(6),
                revision,
                reportState,
                snapshotPayload(snapshotId, periodStart, revision, reportState),
                "a".repeat(64)
        );
        return snapshotId;
    }
}
