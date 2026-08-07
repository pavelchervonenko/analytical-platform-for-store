package com.storeanalytics.interpretation.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.integration.llm.yandex.YandexLlmProperties;
import com.storeanalytics.interpretation.config.LlmAnalysisPlannerProperties;
import com.storeanalytics.interpretation.config.LlmGenerationProperties;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
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
class LlmAnalysisPlanningServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-03T05:00:00Z");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 7, 27);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 8, 2);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private LlmAnalysisPlanningStore planningStore;

    @Autowired
    private LlmAnalysisJobStore jobStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void concurrentReconciliationCreatesOneJobFromCreatedSnapshot()
            throws Exception {
        TestStore store = createStore();
        SnapshotFixture snapshot = createSnapshot(
                store,
                1,
                null,
                "READY",
                "a".repeat(64),
                "CREATED"
        );
        LlmAnalysisPlanningService firstService = service(NOW);
        LlmAnalysisPlanningService secondService = service(NOW.plusSeconds(1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<LlmAnalysisPlanningResult> first = executor.submit(() ->
                    concurrentPlan(firstService, ready, start));
            Future<LlmAnalysisPlanningResult> second = executor.submit(() ->
                    concurrentPlan(secondService, ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }

        assertThat(jobCount(snapshot.snapshotId())).isOne();
        LlmAnalysisJob job = job(snapshot.snapshotId());
        assertThat(job.status()).isEqualTo(LlmAnalysisJobStatus.PENDING);
        assertThat(job.phase()).isEqualTo(LlmAnalysisPhase.PREPARE);
        assertThat(job.triggerType()).isEqualTo(LlmAnalysisTriggerType.INITIAL);
        assertThat(job.generationRevision()).isOne();
        assertThat(job.providerCode()).isEqualTo("YANDEX");
        assertThat(job.requestedModel()).isEqualTo("gpt://folder/yandexgpt/latest");
        assertThat(job.inputHash()).matches("[a-f0-9]{64}");
        assertThat(job.deadlineAt()).isIn(
                NOW.plus(Duration.ofMinutes(5)),
                NOW.plusSeconds(1).plus(Duration.ofMinutes(5))
        );
        assertThat(planningStore.eligibleSnapshots(100)).noneMatch(candidate ->
                candidate.snapshotId().equals(snapshot.snapshotId()));
    }

    @Test
    void plansOnlyLatestEligibleCreatedRevision() {
        TestStore store = createStore();
        SnapshotFixture initial = createSnapshot(
                store,
                1,
                null,
                "READY",
                "b".repeat(64),
                "CREATED"
        );
        SnapshotFixture revision = createSnapshot(
                store,
                2,
                initial.snapshotId(),
                "PARTIAL",
                "c".repeat(64),
                "CREATED"
        );
        TestStore blockedStore = createStore();
        SnapshotFixture blocked = createSnapshot(
                blockedStore,
                1,
                null,
                "BLOCKED",
                "d".repeat(64),
                "CREATED"
        );
        TestStore unchangedStore = createStore();
        SnapshotFixture unchanged = createSnapshot(
                unchangedStore,
                1,
                null,
                "READY",
                "e".repeat(64),
                null
        );

        service(NOW).plan();

        assertThat(jobCount(initial.snapshotId())).isZero();
        assertThat(job(revision.snapshotId()).triggerType())
                .isEqualTo(LlmAnalysisTriggerType.SNAPSHOT_REVISION);
        assertThat(jobCount(blocked.snapshotId())).isZero();
        assertThat(jobCount(unchanged.snapshotId())).isZero();
    }

    private LlmAnalysisPlanningResult concurrentPlan(
            LlmAnalysisPlanningService service,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        return service.plan();
    }

    private LlmAnalysisPlanningService service(Instant now) {
        LlmAnalysisPlannerProperties plannerProperties =
                new LlmAnalysisPlannerProperties(
                        true,
                        Duration.ofMinutes(1),
                        100,
                        Duration.ofMinutes(5)
                );
        LlmAnalysisRequestFactory requestFactory = new LlmAnalysisRequestFactory(
                new LlmGenerationProperties(
                        "weekly-interpretation-v1",
                        1,
                        new BigDecimal("0.2"),
                        4000,
                        2
                ),
                new YandexLlmProperties(
                        "folder",
                        "secret",
                        "gpt://folder/yandexgpt/latest",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(90)
                ),
                plannerProperties
        );
        return new LlmAnalysisPlanningService(
                planningStore,
                jobStore,
                requestFactory,
                plannerProperties,
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    private TestStore createStore() {
        UUID connectionId = jdbcTemplate.queryForObject(
                "SELECT id FROM integration_connections WHERE connection_key = ?",
                UUID.class,
                "livesklad-default"
        );
        UUID storeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stores (
                    id, connection_id, source_system, external_id, name, timezone
                ) VALUES (?, ?, 'LIVESKLAD', ?, 'LLM planner test store', ?)
                """,
                storeId,
                connectionId,
                "llm-planner-" + storeId,
                "Europe/Kaliningrad"
        );
        return new TestStore(storeId, connectionId);
    }

    private SnapshotFixture createSnapshot(
            TestStore store,
            int revision,
            UUID supersedesSnapshotId,
            String qualityStatus,
            String factsHash,
            String jobOutcome
    ) {
        Instant completedAt = NOW.minusSeconds(120L - revision);
        UUID syncJobId = createSuccessfulSync(store.connectionId(), completedAt);
        UUID snapshotId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshots (
                    id, store_id, snapshot_type, period_start, period_end, timezone,
                    revision, supersedes_snapshot_id, revision_reason_code,
                    source_sync_job_id, source_sync_completed_at, source_data_cutoff,
                    facts_schema_version, metrics_contract_version, calculation_version,
                    quality_policy_version, quality_status, facts_payload, facts_hash,
                    created_at
                ) VALUES (
                    ?, ?, 'WEEKLY', ?, ?, 'Europe/Kaliningrad', ?, ?, ?, ?, ?, ?,
                    1, 'weekly-metrics-v1', 'weekly-snapshot-v1', 'weekly-quality-v1',
                    ?, '{}'::jsonb, ?, ?
                )
                """,
                snapshotId,
                store.storeId(),
                PERIOD_START,
                PERIOD_END,
                revision,
                supersedesSnapshotId,
                revision == 1 ? "INITIAL" : "AUTO_REVISION",
                syncJobId,
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                qualityStatus,
                factsHash,
                Timestamp.from(completedAt.plusSeconds(1))
        );
        if (jobOutcome != null) {
            createTerminalSnapshotJob(
                    store.storeId(),
                    syncJobId,
                    snapshotId,
                    supersedesSnapshotId,
                    completedAt,
                    jobOutcome
            );
        }
        return new SnapshotFixture(snapshotId, syncJobId);
    }

    private UUID createSuccessfulSync(UUID connectionId, Instant completedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO sync_jobs (
                    id, connection_id, job_type, status, phase, period_start, period_end,
                    cursor_start, current_window_end, window_size_minutes, max_attempts,
                    next_attempt_at, started_at, finished_at
                ) VALUES (
                    ?, ?, 'INCREMENTAL', 'SUCCESS', 'RETURNS', ?, ?, ?, ?, 1440, 5,
                    ?, ?, ?
                )
                """,
                id,
                connectionId,
                Timestamp.from(completedAt.minus(Duration.ofDays(7))),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt),
                Timestamp.from(completedAt.minusSeconds(60)),
                Timestamp.from(completedAt)
        );
        return id;
    }

    private void createTerminalSnapshotJob(
            UUID storeId,
            UUID syncJobId,
            UUID snapshotId,
            UUID baseSnapshotId,
            Instant completedAt,
            String outcome
    ) {
        UUID jobId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO analytics_snapshot_jobs (
                    id, store_id, job_type, period_start, period_end, timezone,
                    source_sync_job_id, source_data_cutoff, facts_schema_version,
                    metrics_contract_version, calculation_version, quality_policy_version,
                    base_snapshot_id, status, outcome, result_snapshot_id, attempt_count,
                    max_attempts, next_attempt_at, started_at, finished_at
                ) VALUES (
                    ?, ?, ?, ?, ?, 'Europe/Kaliningrad', ?, ?, 1,
                    'weekly-metrics-v1', 'weekly-snapshot-v1', 'weekly-quality-v1',
                    ?, 'SUCCESS', ?, ?, 1, 5, ?, ?, ?
                )
                """,
                jobId,
                storeId,
                baseSnapshotId == null ? "INITIAL" : "AUTO_REVISION",
                PERIOD_START,
                PERIOD_END,
                syncJobId,
                Timestamp.from(completedAt),
                baseSnapshotId,
                outcome,
                snapshotId,
                Timestamp.from(completedAt),
                Timestamp.from(completedAt.minusSeconds(30)),
                Timestamp.from(completedAt)
        );
    }

    private long jobCount(UUID snapshotId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_analysis_jobs WHERE snapshot_id = ?",
                Long.class,
                snapshotId
        );
    }

    private LlmAnalysisJob job(UUID snapshotId) {
        UUID id = jdbcTemplate.queryForObject(
                "SELECT id FROM llm_analysis_jobs WHERE snapshot_id = ?",
                UUID.class,
                snapshotId
        );
        return jobStore.findById(id).orElseThrow();
    }

    private record TestStore(UUID storeId, UUID connectionId) {
    }

    private record SnapshotFixture(UUID snapshotId, UUID syncJobId) {
    }
}
