package com.storeanalytics.interpretation.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.interpretation.generation.LlmProviderPreflight;
import com.storeanalytics.interpretation.generation.LlmProviderResponseReceipt;
import com.storeanalytics.interpretation.review.ai.PreparedWeeklyReviewAiRequest;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiAttempt;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiCompletionService;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiJob;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiJobStore;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiEnrichmentStore;
import com.storeanalytics.interpretation.review.ai.SellerWeeklyReviewAiEnricher;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiProviderRequestCommand;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiProviderRequestFactory;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiSelection;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiSemanticValidator;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiSnapshotNotCurrentException;
import com.storeanalytics.interpretation.review.ai.WeeklyReviewAiValidationResult;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic-only DB races; no provider, external network, production baseline or payroll changes. */
@SpringBootTest(properties = {"app.attach.attribution-enabled=true",
        "app.interpretation.weekly-review.enabled=true",
        "app.interpretation.seller-weekly-review.enabled=true"})
@Testcontainers(disabledWithoutDocker = true)
class SellerWeeklyAiSourceFenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final LocalDate START = LocalDate.parse("2026-09-28");
    private static final String OWNER = "synthetic-fenced-worker";
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        MutableClock fenceClock() {
            return new MutableClock();
        }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> value = new AtomicReference<>(NOW);
        @Override
        public Instant instant() {
            return value.get();
        }
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }
        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private MutableClock clock;
    @Autowired private SellerWeeklyHistoricalPlanningService planner;
    @Autowired private SellerWeeklyAiSourceFence fence;
    @Autowired private WeeklyReviewAiJobStore jobs;
    @Autowired private WeeklyReviewAiProviderRequestFactory factory;
    @Autowired private WeeklyReviewAiSemanticValidator validator;
    @Autowired private WeeklyReviewAiCompletionService completion;
    @Autowired private WeeklyReviewAiEnrichmentStore enrichments;
    @Autowired private SellerWeeklyReviewAiEnricher enricher;
    @Autowired private WeeklyReviewSnapshotStore snapshots;
    private UUID store;

    @BeforeEach
    void resetClock() {
        clock.value.set(NOW);
    }

    @AfterEach
    void retireSyntheticJobs() {
        jdbc.update("UPDATE weekly_review_ai_jobs SET status='FAILED',lease_owner=NULL,lease_until=NULL "
                + "WHERE status IN ('PENDING','RUNNING','RETRY_WAIT')");
    }

    @Test
    void fenceRejectsMissingReadonlyOrRepeatableReadTransaction() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> fence.lockAndIsCurrent(unknown))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var readonly = transaction();
        readonly.setReadOnly(true);
        assertThatThrownBy(() -> readonly.execute(status -> fence.lockAndIsCurrent(unknown)))
                .isInstanceOf(IllegalStateException.class);
        var repeatable = transaction();
        repeatable.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> repeatable.execute(status -> fence.lockAndIsCurrent(unknown)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void timeoutsAreLocalToTheShortPublicationTransaction() {
        Prepared prepared = prepare();
        String originalLockTimeout = jdbc.queryForObject("SHOW lock_timeout", String.class);
        String originalStatementTimeout = jdbc.queryForObject("SHOW statement_timeout", String.class);
        transaction().executeWithoutResult(status -> {
            assertThat(fence.lockAndIsCurrent(prepared.job().snapshotId())).isTrue();
            assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
            assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("30s");
        });
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo(originalLockTimeout);
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo(originalStatementTimeout);
    }

    @Test
    void currentExactSnapshotStartsAndPublishesWithoutChangingFinancialPayload() {
        Prepared prepared = prepare();
        String payload = jdbc.queryForObject("SELECT report_payload::text FROM weekly_review_snapshots WHERE id=?",
                String.class, prepared.job().snapshotId());
        WeeklyReviewAiAttempt attempt = start(prepared);
        complete(prepared, attempt);
        assertThat(jdbc.queryForObject("SELECT status FROM weekly_review_ai_jobs WHERE id=?",
                String.class, prepared.job().id())).isEqualTo("SUCCEEDED");
        assertThat(enrichments(prepared)).isOne();
        var enrichment = enrichments.findPublishedSeller(prepared.job().snapshotId(), NOW).orElseThrow();
        var report = snapshots.findV3ById(prepared.job().snapshotId()).orElseThrow().response();
        assertThat(enricher.applyIfCompatible(report, enrichment).orElseThrow().aiEnhancement().state())
                .isEqualTo(WeeklyReviewResponse.AiState.READY);
        assertThat(enrichments.findPublished(prepared.job().snapshotId(), NOW)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT report_payload::text FROM weekly_review_snapshots WHERE id=?",
                String.class, prepared.job().snapshotId())).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_response_receipts WHERE attempt_id=?",
                Long.class, attempt.id())).isOne();
    }

    @Test
    void staleAutomaticEnqueueDoesNotConsumeTheOnlyJobForThisWeek() {
        var snapshot = snapshot();
        invalidate();
        assertThat(jobs.enqueueAutomaticSellerWeek(snapshot.id(), "YANDEX", "synthetic-model",
                2, NOW, Duration.ofHours(2))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_jobs WHERE snapshot_id=?",
                Long.class, snapshot.id())).isZero();
        var refreshed = planner.evaluate(store, START, "UTC").review().snapshot().orElseThrow();
        assertThat(refreshed.id()).isEqualTo(snapshot.id());
        assertThat(jobs.enqueueAutomaticSellerWeek(refreshed.id(), "YANDEX", "synthetic-model",
                2, NOW, Duration.ofHours(2))).isTrue();
        assertThat(jobs.enqueueAutomaticSellerWeek(refreshed.id(), "YANDEX", "synthetic-model",
                2, NOW, Duration.ofHours(2))).isFalse();
    }

    @Test
    void changedSourceCannotReserveOrStartAnAttempt() {
        Prepared prepared = prepare();
        invalidate();
        assertThatThrownBy(() -> start(prepared)).isInstanceOf(WeeklyReviewAiSnapshotNotCurrentException.class);
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM weekly_review_ai_jobs WHERE id=?",
                Integer.class, prepared.job().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_attempts WHERE job_id=?",
                Long.class, prepared.job().id())).isZero();
    }

    @Test
    void automaticUnpaidBindingChangesOnlyAfterTheNewRevisionIsCurrent() {
        Instant now = (Instant.now().isBefore(NOW) ? NOW : Instant.now())
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        clock.value.set(now);
        var original = snapshot();
        assertThat(jobs.enqueueAutomaticSellerWeek(original.id(), "YANDEX", "synthetic-model",
                2, now, Duration.ofHours(2))).isTrue();
        var job = jobs.findBySnapshot(original.id()).orElseThrow();
        jdbc.update("UPDATE employees SET is_active=false WHERE id IN "
                + "(SELECT employee_id FROM employee_store_assignments WHERE store_id=?)", store);
        assertThat(jobs.enqueueAutomaticSellerWeek(original.id(), "YANDEX", "synthetic-model",
                2, now, Duration.ofHours(2))).isFalse();
        assertThat(jobs.findById(job.id()).orElseThrow().snapshotId()).isEqualTo(original.id());
        var refreshed = planner.evaluate(store, START, "UTC").review().snapshot().orElseThrow();
        assertThat(refreshed.id()).isNotEqualTo(original.id());
        assertThat(jobs.enqueueAutomaticSellerWeek(refreshed.id(), "YANDEX", "synthetic-model",
                2, now, Duration.ofHours(2))).isTrue();
        var rebound = jobs.findById(job.id()).orElseThrow();
        assertThat(rebound.snapshotId()).isEqualTo(refreshed.id());
        assertThat(rebound.deadlineAt()).isEqualTo(job.deadlineAt());
        assertThat(rebound.attemptCount()).isZero();
    }

    @Test
    void paidAutomaticJobKeepsItsBindingWhenTheNewSourceRevisionIsReady() {
        Instant now = (Instant.now().isBefore(NOW) ? NOW : Instant.now())
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        clock.value.set(now);
        var original = snapshot();
        assertThat(jobs.enqueueAutomaticSellerWeek(original.id(), "YANDEX", "synthetic-model",
                2, now, Duration.ofHours(2))).isTrue();
        var claim = jobs.claimNext(OWNER, Duration.ofMinutes(4), now).orElseThrow();
        var request = factory.prepare(new WeeklyReviewAiProviderRequestCommand(claim.id(), original,
                "YANDEX", "synthetic-model", new BigDecimal("0.1"), 1400, now, Duration.ofSeconds(180),
                claim.deadlineAt(), List.of()));
        var prepared = new Prepared(claim, request);
        var attempt = start(prepared);
        jdbc.update("UPDATE employees SET is_active=false WHERE id IN "
                + "(SELECT employee_id FROM employee_store_assignments WHERE store_id=?)", store);
        var refreshed = planner.evaluate(store, START, "UTC").review().snapshot().orElseThrow();
        assertThat(refreshed.id()).isNotEqualTo(original.id());
        assertThat(jobs.enqueueAutomaticSellerWeek(refreshed.id(), "YANDEX", "synthetic-model",
                2, now, Duration.ofHours(2))).isFalse();
        complete(prepared, attempt);
        assertThat(jobs.findById(claim.id()).orElseThrow().snapshotId()).isEqualTo(original.id());
        assertThat(jobs.findById(claim.id()).orElseThrow().lastErrorCode()).isEqualTo("SNAPSHOT_NOT_CURRENT");
        assertThat(jdbc.queryForObject("SELECT actual_cost FROM weekly_review_ai_response_receipts WHERE attempt_id=?",
                BigDecimal.class, attempt.id())).isEqualByComparingTo("2.00");
        assertThat(jobs.findBySnapshot(refreshed.id())).isEmpty();
    }

    @Test
    void changedSourceAfterPaidResponsePreservesBillingButNeverPublishesOrRetries() {
        Prepared prepared = prepare();
        WeeklyReviewAiAttempt attempt = start(prepared);
        invalidate();
        complete(prepared, attempt);
        assertThat(enrichments(prepared)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM weekly_review_ai_jobs WHERE id=?",
                String.class, prepared.job().id())).isEqualTo("SNAPSHOT_NOT_CURRENT");
        assertThat(jdbc.queryForObject("SELECT status FROM weekly_review_ai_jobs WHERE id=?",
                String.class, prepared.job().id())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT actual_cost FROM weekly_review_ai_response_receipts WHERE attempt_id=?",
                BigDecimal.class, attempt.id())).isEqualByComparingTo("2.00");
    }

    @Test
    void startRechecksSourceAfterWaitingForCommit() {
        Prepared prepared = prepare();
        waitBehindSourceLock(() -> start(prepared), this::invalidate, "WeeklyReviewAiSnapshotNotCurrentException");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM weekly_review_ai_jobs WHERE id=?",
                Integer.class, prepared.job().id())).isZero();
    }

    @Test
    void staleCallerTimestampCannotReviveLeaseAfterWaitingForLock() {
        Prepared prepared = prepare();
        waitBehindSourceLock(() -> start(prepared), () -> clock.value.set(NOW.plusSeconds(240)),
                "WeeklyReviewAiLeaseLostException");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM weekly_review_ai_jobs WHERE id=?",
                Integer.class, prepared.job().id())).isZero();
    }

    @Test
    void completionRechecksAfterWaitingAndPreservesIndependentReceipt() {
        Prepared prepared = prepare();
        WeeklyReviewAiAttempt attempt = start(prepared);
        waitBehindSourceLock(() -> {
            complete(prepared, attempt);
            return null;
        }, this::invalidate, null);
        assertThat(enrichments(prepared)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_response_receipts WHERE attempt_id=?",
                Long.class, attempt.id())).isOne();
    }

    @Test
    void expiredPreparedRequestCannotStartEvenWithLiveJobLease() {
        Prepared prepared = prepare();
        clock.value.set(NOW.plusSeconds(180));
        assertThatThrownBy(() -> start(prepared))
                .hasMessage("Weekly review AI provider call deadline expired before attempt start");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM weekly_review_ai_jobs WHERE id=?",
                Integer.class, prepared.job().id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_attempts WHERE job_id=?",
                Long.class, prepared.job().id())).isZero();
    }

    @Test
    void completionCannotPublishWithAnExpiredLeaseEvenWhenCallerTimeIsOld() {
        Prepared prepared = prepare();
        WeeklyReviewAiAttempt attempt = start(prepared);
        waitBehindSourceLock(() -> {
            complete(prepared, attempt);
            return null;
        },
                () -> clock.value.set(NOW.plusSeconds(240)), "WeeklyReviewAiLeaseLostException");
        assertThat(enrichments(prepared)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_response_receipts WHERE attempt_id=?",
                Long.class, attempt.id())).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM weekly_review_ai_attempts WHERE id=?",
                String.class, attempt.id())).isEqualTo("STARTED");
    }

    @Test
    void fenceHoldsSourceUntilPublicationTransactionCompletes() throws Exception {
        Prepared prepared = prepare();
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> change = new AtomicReference<>();
        transaction().executeWithoutResult(status -> {
            assertThat(fence.lockAndIsCurrent(prepared.job().snapshotId())).isTrue();
            change.set(CompletableFuture.runAsync(() -> {
                attempted.countDown();
                invalidate();
            }));
            await(attempted);
            assertThatThrownBy(() -> change.get().get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(fence.lockAndIsCurrent(prepared.job().snapshotId())).isTrue();
        });
        change.get().get(10, TimeUnit.SECONDS);
        Boolean current = transaction().execute(status -> fence.lockAndIsCurrent(prepared.job().snapshotId()));
        assertThat(current).isFalse();
    }

    private <T> void waitBehindSourceLock(java.util.concurrent.Callable<T> operation, Runnable change,
            String expected) {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<CompletableFuture<T>> pending = new AtomicReference<>();
        transaction().executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT revision FROM store_analytics_source_state WHERE store_id=? FOR UPDATE",
                    Long.class, store);
            pending.set(CompletableFuture.supplyAsync(() -> {
                entered.countDown();
                try {
                    return operation.call();
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }));
            await(entered);
            assertThatThrownBy(() -> pending.get().get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            change.run();
        });
        if (expected != null) {
            assertThatThrownBy(() -> pending.get().get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).satisfies(failure ->
                        assertThat(failure.getCause().getClass().getSimpleName()).isEqualTo(expected));
        } else {
            assertThat(pending.get()).succeedsWithin(Duration.ofSeconds(10));
        }
    }

    private TransactionTemplate transaction() {
        var template = new TransactionTemplate(transactions);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void invalidate() {
        jdbc.update("UPDATE store_analytics_source_state SET revision=revision+1 WHERE store_id=?", store);
    }

    private long enrichments(Prepared prepared) {
        return jdbc.queryForObject("SELECT count(*) FROM weekly_review_ai_enrichments WHERE snapshot_id=?",
                Long.class, prepared.job().snapshotId());
    }

    private WeeklyReviewAiAttempt start(Prepared prepared) {
        return jobs.startAttempt(prepared.job(), OWNER, prepared.request(),
                new LlmProviderPreflight(1000, 8000, new BigDecimal("3.00"), "RUB"), NOW);
    }

    private void complete(Prepared prepared, WeeklyReviewAiAttempt attempt) {
        var input = prepared.request().input();
        var selection = new WeeklyReviewAiSelection(1, new WeeklyReviewAiSelection.SummarySelection(
                input.summary().allowedSelectors().getFirst(),
                input.factors().isEmpty() ? null : input.factors().getFirst().factorId(), null),
                input.factors().stream().map(item -> new WeeklyReviewAiSelection.FactorSelection(
                        item.factorId(), item.allowedSelectors().getFirst())).toList());
        String body = JsonMapper.builder().build().writeValueAsString(selection);
        WeeklyReviewAiValidationResult valid = validator.validate(input, body);
        assertThat(valid.semanticValidated()).isTrue();
        var response = new LlmProviderResponseReceipt(body, "synthetic-model", "synthetic-request",
                1000, 100, 0, 0, 1100, new BigDecimal("2.00"), "RUB", 50L, 200);
        completion.complete(prepared.job(), attempt, OWNER, prepared.request(), response, valid, NOW);
    }

    private Prepared prepare() {
        var snapshot = snapshot();
        jobs.enqueue(snapshot.id(), "YANDEX", "synthetic-model", 2, NOW, Duration.ofHours(2));
        var claimed = jobs.claimNext(OWNER, Duration.ofMinutes(4), NOW).orElseThrow();
        var request = factory.prepare(new WeeklyReviewAiProviderRequestCommand(claimed.id(), snapshot,
                "YANDEX", "synthetic-model", new BigDecimal("0.1"), 1400, NOW, Duration.ofSeconds(180),
                claimed.deadlineAt(), List.of()));
        return new Prepared(claimed, request);
    }

    private PersistedWeeklyReviewV3Snapshot snapshot() {
        store = seed();
        var snapshot = planner.evaluate(store, START, "UTC").review().snapshot().orElseThrow();
        assertThat(snapshot.response().reportState()).isIn(WeeklyReviewResponse.ReportState.READY,
                WeeklyReviewResponse.ReportState.PARTIAL);
        return snapshot;
    }

    private record Prepared(WeeklyReviewAiJob job, PreparedWeeklyReviewAiRequest request) { }

    private UUID seed() {
        UUID connection = jdbc.queryForObject("SELECT id FROM integration_connections "
                + "WHERE connection_key='livesklad-default'", UUID.class);
        UUID selected = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        jdbc.update("INSERT INTO stores(id,connection_id,external_id,name,timezone) "
                + "VALUES (?,?,?,'Synthetic AI fence','UTC')", selected, connection, selected.toString());
        jdbc.update("INSERT INTO employees(id,connection_id,external_id,full_name) "
                + "VALUES (?,?,?,'Synthetic seller')", employee, connection, employee.toString());
        jdbc.update("INSERT INTO employee_store_assignments(employee_id,store_id,participates_in_ranking) "
                + "VALUES (?,?,true)", employee, selected);
        jdbc.update("INSERT INTO store_seller_membership_state(store_id,authoritative_from,baseline_source) "
                + "VALUES (?,'2026-09-21T00:00:00Z','SYNTHETIC_ONLY')", selected);
        jdbc.update("""
                INSERT INTO seller_membership_history(store_id,employee_id,employee_active,assignment_active,
                    participates_in_ranking,valid_from,change_source,effective_time_source)
                VALUES (?,?,true,true,true,'2026-09-21T00:00:00Z','BASELINE','APPROVED_BASELINE')
                """, selected, employee);
        UUID saleRun = UUID.randomUUID();
        for (String scope : List.of("SALES", "RETURNS", "ORDERS")) {
            jdbc.update("""
                    INSERT INTO sync_runs(id,connection_id,store_id,source_system,trigger_type,sync_scope,status,
                        period_start,period_end,started_at,finished_at)
                    VALUES (?,?,?,'LIVESKLAD','MANUAL',?,'SUCCESS','2026-09-21T00:00:00Z',
                        '2026-10-05T00:00:00Z',?,?)
                    """, "SALES".equals(scope) ? saleRun : UUID.randomUUID(), connection, selected, scope,
                    Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.minusSeconds(30)));
        }
        jdbc.update("""
                INSERT INTO sales_documents(connection_id,external_id,store_id,employee_id,document_kind,
                    source_document_type,occurred_at,business_date,net_amount,cost_amount,last_sync_run_id)
                VALUES (?,?,?,?,'SALE','sale','2026-09-29T12:00:00Z','2026-09-29',100,50,?)
                """, connection, selected.toString(), selected, employee, saleRun);
        return selected;
    }
}
